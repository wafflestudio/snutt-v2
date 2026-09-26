package com.wafflestudio.snutt.core.domain.device.service

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.transaction.afterCommit
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class DeviceService(
    private val userDeviceRepository: UserDeviceRepository,
    private val userRepository: UserRepository,
    private val pushClient: PushClient,
) {
    @Transactional
    fun addRegistrationId(
        userId: Long,
        registrationId: String,
        clientInfo: ClientInfo,
    ) {
        val user = userRepository.findByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val deviceByRegistrationId = userDeviceRepository.findByFcmRegistrationIdAndIsDeletedFalse(registrationId)
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
        device.fcmRegistrationId = registrationId
        device.osType = clientInfo.osType
        device.osVersion = clientInfo.osVersion
        device.deviceId = clientInfo.deviceId
        device.deviceModel = clientInfo.deviceModel
        device.appType = clientInfo.appType
        device.appVersion = clientInfo.appVersion
        userDeviceRepository.save(device)
        afterCommit { pushClient.subscribeGlobalTopic(registrationId) }
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
}
