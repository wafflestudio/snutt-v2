package com.wafflestudio.snutt.core.domain.device.service

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeviceService(
    private val userDeviceRepository: UserDeviceRepository,
    private val pushClient: PushClient,
) {
    @Transactional
    fun addRegistrationId(
        user: User,
        registrationId: String,
        clientInfo: ClientInfo,
    ) {
        val userId = requireNotNull(user.id) { "persisted user must have an id" }
        val device =
            clientInfo.deviceId?.let { userDeviceRepository.findByUserIdAndDeviceIdAndIsDeletedFalse(userId, it) }
                ?: userDeviceRepository.findByUserIdAndFcmRegistrationIdAndIsDeletedFalse(userId, registrationId)
        if (device == null) {
            userDeviceRepository.save(
                UserDevice(
                    user = user,
                    osType = clientInfo.osType,
                    osVersion = clientInfo.osVersion,
                    deviceId = clientInfo.deviceId,
                    deviceModel = clientInfo.deviceModel,
                    appType = clientInfo.appType,
                    appVersion = clientInfo.appVersion,
                    fcmRegistrationId = registrationId,
                ),
            )
        } else {
            device.fcmRegistrationId = registrationId
            device.osType = clientInfo.osType
            device.osVersion = clientInfo.osVersion
            device.deviceId = clientInfo.deviceId
            device.deviceModel = clientInfo.deviceModel
            device.appType = clientInfo.appType
            device.appVersion = clientInfo.appVersion
        }
        pushClient.subscribeGlobalTopic(registrationId)
    }

    @Transactional
    fun removeRegistrationId(
        user: User,
        registrationId: String,
    ) {
        val userId = requireNotNull(user.id) { "persisted user must have an id" }
        userDeviceRepository
            .findByUserIdAndFcmRegistrationIdAndIsDeletedFalse(userId, registrationId)
            ?.let { it.isDeleted = true }
        pushClient.unsubscribeGlobalTopic(registrationId)
    }
}
