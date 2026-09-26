package com.wafflestudio.snutt.core.domain.notification.service

import com.wafflestudio.snutt.core.common.push.GLOBAL_TOPIC
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.push.PushMessage
import com.wafflestudio.snutt.core.common.push.TargetedPushMessage
import com.wafflestudio.snutt.core.common.transaction.afterCommit
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.device.service.DeviceService
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PushService(
    private val pushClient: PushClient,
    private val userDeviceRepository: UserDeviceRepository,
    private val deviceService: DeviceService,
    private val pushPreferenceRepository: PushPreferenceRepository,
    private val notificationRepository: NotificationRepository,
) {
    fun sendTargetedPushes(
        messagesByUserId: Map<Long, PushMessage>,
        preferenceType: PushPreferenceType,
    ) {
        deliver(resolveMessages(messagesByUserId, preferenceType))
    }

    fun sendGlobalPushAndNotification(
        message: PushMessage,
        type: NotificationType,
    ) {
        notificationRepository.save(
            Notification(userId = null, title = message.title, message = message.body, type = type, deeplink = message.urlScheme),
        )
        pushClient.sendTopicMessage(GLOBAL_TOPIC, message)
    }

    @Transactional
    fun sendPushAndNotification(
        userIds: Collection<Long>,
        message: PushMessage,
        type: NotificationType,
        preferenceType: PushPreferenceType,
    ) {
        if (userIds.isEmpty()) return
        notificationRepository.saveAll(
            userIds.map {
                Notification(
                    userId = it,
                    title = message.title,
                    message = message.body,
                    type = type,
                    deeplink = message.urlScheme,
                )
            },
        )
        val messages = resolveMessages(userIds.associateWith { message }, preferenceType)
        afterCommit { deliver(messages) }
    }

    private fun resolveMessages(
        messagesByUserId: Map<Long, PushMessage>,
        preferenceType: PushPreferenceType,
    ): List<TargetedPushMessage> {
        if (messagesByUserId.isEmpty()) return emptyList()
        val disabledUserIds = pushPreferenceRepository.findDisabledUserIds(messagesByUserId.keys, preferenceType).toSet()
        val targets = messagesByUserId.filterKeys { it !in disabledUserIds }
        if (targets.isEmpty()) return emptyList()
        return userDeviceRepository.findPushTargets(targets.keys).mapNotNull { target ->
            targets[target.userId]?.let { TargetedPushMessage(target.fcmRegistrationId, it) }
        }
    }

    private fun deliver(messages: List<TargetedPushMessage>) {
        if (messages.isEmpty()) return
        val result = pushClient.sendMessages(messages)
        deviceService.markDeletedByRegistrationIds(result.invalidRegistrationIds)
    }
}
