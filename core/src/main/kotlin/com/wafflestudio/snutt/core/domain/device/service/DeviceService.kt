package com.wafflestudio.snutt.core.domain.device.service

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.transaction.afterCommit
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
class DeviceService(
    private val userDeviceRepository: UserDeviceRepository,
    private val userRepository: UserRepository,
    private val pushClient: PushClient,
    transactionManager: PlatformTransactionManager,
) {
    // READ COMMITTED: 토큰으로 잠글 기기가 아직 없을 때 REPEATABLE READ의 FOR UPDATE는 인덱스 gap 락을 잡아, 동시에 insert하는 요청끼리
    // insert intention 데드락이 난다. READ COMMITTED에서는 gap 락 없이 unique 검사에서 한쪽만 실패하고 다시 시도한다.
    private val transactionTemplate =
        TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }

    /**
     * 토큰을 이 사용자의 기기로 등록한다. 토큰은 활성 기기 하나만 가리키므로 다른 사용자가 쓰던 토큰이면 그 기기를 지운다.
     * 다른 사용자들이 같은 토큰을 동시에 등록하면(한 기기에서 계정 전환) 토큰의 활성 기기 행을 잠가 줄을 세운다. 아직 활성 기기가 없어
     * 잠글 행이 없으면 둘 다 insert해 한쪽이 unique 위반으로 실패하므로, 새 트랜잭션에서 다시 시도한다(다음 시도는 먼저 커밋된 기기를 잠근다).
     */
    fun addRegistrationId(
        userId: Long,
        registrationId: String,
        clientInfo: ClientInfo,
    ) {
        // user_device.fcm_registration_id VARCHAR(512). 자르면 다른 토큰과 unique 충돌할 수 있으므로 거부한다.
        if (registrationId.length > MAX_REGISTRATION_ID_LENGTH) throw SnuttException(ErrorType.INVALID_PARAMETER)
        var attempt = 1
        while (true) {
            try {
                transactionTemplate.executeWithoutResult { register(userId, registrationId, clientInfo) }
                return
            } catch (e: DataIntegrityViolationException) {
                if (attempt >= REGISTER_ATTEMPTS) throw e
            } catch (e: PessimisticLockingFailureException) {
                if (attempt >= REGISTER_ATTEMPTS) throw e
            }
            attempt++
        }
    }

    private fun register(
        userId: Long,
        registrationId: String,
        clientInfo: ClientInfo,
    ) {
        val user = userRepository.findForUpdateByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val deviceByRegistrationId = userDeviceRepository.findForUpdateByFcmRegistrationIdAndIsDeletedFalse(registrationId)
        val deviceByDeviceId =
            clientInfo.deviceId?.let { userDeviceRepository.findByUserIdAndDeviceIdAndIsDeletedFalse(userId, it) }
        val device =
            if (deviceByRegistrationId?.user?.id == userId) {
                if (deviceByDeviceId != null && deviceByDeviceId.id != deviceByRegistrationId.id) {
                    deviceByDeviceId.isDeleted = true
                }
                deviceByRegistrationId
            } else {
                if (deviceByRegistrationId != null) {
                    deviceByRegistrationId.isDeleted = true
                    userDeviceRepository.flush()
                }
                deviceByDeviceId ?: UserDevice(user = user, fcmRegistrationId = registrationId)
            }
        val previousLanguage = if (device.id == null) null else device.language
        device.fcmRegistrationId = registrationId
        device.osType = clientInfo.osType.value
        device.osVersion = clientInfo.osVersion
        device.deviceId = clientInfo.deviceId
        device.deviceModel = clientInfo.deviceModel
        device.appType = clientInfo.appType
        device.appVersion = clientInfo.appVersion
        device.language = clientInfo.language
        userDeviceRepository.save(device)
        afterCommit { pushClient.subscribeGlobalTopic(registrationId, clientInfo.language, previousLanguage) }
    }

    @Transactional
    fun removeRegistrationId(
        userId: Long,
        registrationId: String,
    ) {
        val device =
            userDeviceRepository.findByUserIdAndFcmRegistrationIdAndIsDeletedFalse(userId, registrationId)
                ?: return
        device.isDeleted = true
        afterCommit { pushClient.unsubscribeGlobalTopic(registrationId) }
    }

    @Transactional
    fun removeAllByUserId(userId: Long) {
        val devices = userDeviceRepository.findAllByUserIdInAndIsDeletedFalse(listOf(userId))
        devices.forEach { it.isDeleted = true }
        val registrationIds = devices.map { it.fcmRegistrationId }
        afterCommit { registrationIds.forEach(pushClient::unsubscribeGlobalTopic) }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markDeletedByRegistrationIds(registrationIds: Collection<String>) {
        if (registrationIds.isEmpty()) return
        userDeviceRepository.markDeletedByFcmRegistrationIds(registrationIds)
    }

    companion object {
        const val MAX_REGISTRATION_ID_LENGTH = 512
        private const val REGISTER_ATTEMPTS = 5
    }
}
