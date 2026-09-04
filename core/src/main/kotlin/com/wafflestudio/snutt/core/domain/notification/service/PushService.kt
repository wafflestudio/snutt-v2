package com.wafflestudio.snutt.core.domain.notification.service

import com.wafflestudio.snutt.core.common.push.GLOBAL_TOPIC
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.push.TargetedPushMessage
import com.wafflestudio.snutt.core.common.push.TopicPushMessage
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.device.service.DeviceService
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class TargetedPush(
    val title: String,
    val body: String,
    val urlScheme: String? = null,
)

@Service
class PushService(
    private val pushClient: PushClient,
    private val userDeviceRepository: UserDeviceRepository,
    private val deviceService: DeviceService,
    private val pushPreferenceRepository: PushPreferenceRepository,
    private val notificationRepository: NotificationRepository,
) {
    @Transactional(readOnly = true)
    fun sendTargetedPushes(
        messagesByUserId: Map<Long, TargetedPush>,
        preferenceType: PushPreferenceType,
    ) {
        sendToUsers(messagesByUserId, preferenceType)
    }

    private fun sendToUsers(
        messagesByUserId: Map<Long, TargetedPush>,
        preferenceType: PushPreferenceType,
    ) {
        if (messagesByUserId.isEmpty()) return
        val disabledUserIds =
            pushPreferenceRepository
                .findByUserIdInAndTypeAndIsEnabledFalse(messagesByUserId.keys, preferenceType)
                .map { it.user.id!! }
                .toSet()
        val targets = messagesByUserId.filterKeys { it !in disabledUserIds }
        if (targets.isEmpty()) return
        val devices = userDeviceRepository.findAllByUserIdInAndIsDeletedFalse(targets.keys)
        sendToDevicesWithCleanup(
            devices.mapNotNull { device ->
                targets[device.user.id]?.let {
                    TargetedPushMessage(it.title, it.body, it.urlScheme, device.fcmRegistrationId)
                }
            },
        )
    }

    fun sendGlobalPushAndNotification(
        title: String,
        body: String,
        type: NotificationType,
        urlScheme: String? = null,
    ) {
        notificationRepository.save(
            Notification(userId = null, title = title, message = body, type = type, deeplink = urlScheme),
        )
        pushClient.sendTopicMessage(TopicPushMessage(title, body, urlScheme, GLOBAL_TOPIC))
    }

    @Transactional
    fun sendPushAndNotification(
        userIds: Collection<Long>,
        title: String,
        body: String,
        type: NotificationType,
        preferenceType: PushPreferenceType,
        urlScheme: String? = null,
    ) {
        if (userIds.isEmpty()) return
        sendToUsers(
            userIds.associateWith { TargetedPush(title, body, urlScheme) },
            preferenceType,
        )
        notificationRepository.saveAll(
            userIds.map { userId -> Notification(userId = userId, title = title, message = body, type = type, deeplink = urlScheme) },
        )
    }

    private fun sendToDevicesWithCleanup(messages: List<TargetedPushMessage>) {
        val result = pushClient.sendMessages(messages)
        deviceService.markDeletedByRegistrationIds(result.invalidRegistrationIds)
    }
}
