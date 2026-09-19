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
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

data class TargetedPush(
    val title: String,
    val body: String,
    val urlScheme: String? = null,
    val isUrgentOnAndroid: Boolean = false,
    val shouldSendAsDataMessage: Boolean = false,
    val data: Map<String, String> = emptyMap(),
)

@Service
class PushService(
    private val pushClient: PushClient,
    private val userDeviceRepository: UserDeviceRepository,
    private val deviceService: DeviceService,
    private val pushPreferenceRepository: PushPreferenceRepository,
    private val notificationRepository: NotificationRepository,
) {
    fun sendTargetedPushes(
        messagesByUserId: Map<Long, TargetedPush>,
        preferenceType: PushPreferenceType,
    ) {
        deliver(resolveMessages(messagesByUserId, preferenceType))
    }

    private fun resolveMessages(
        messagesByUserId: Map<Long, TargetedPush>,
        preferenceType: PushPreferenceType,
    ): List<TargetedPushMessage> {
        if (messagesByUserId.isEmpty()) return emptyList()
        val disabledUserIds = pushPreferenceRepository.findDisabledUserIds(messagesByUserId.keys, preferenceType).toSet()
        val targets = messagesByUserId.filterKeys { it !in disabledUserIds }
        if (targets.isEmpty()) return emptyList()
        return userDeviceRepository.findPushTargets(targets.keys).mapNotNull { target ->
            targets[target.userId]?.let {
                TargetedPushMessage(
                    it.title,
                    it.body,
                    it.urlScheme,
                    target.fcmRegistrationId,
                    it.isUrgentOnAndroid,
                    it.shouldSendAsDataMessage,
                    it.data,
                )
            }
        }
    }

    fun sendGlobalPushAndNotification(
        title: String,
        body: String,
        type: NotificationType,
        urlScheme: String? = null,
        isUrgentOnAndroid: Boolean = false,
        shouldSendAsDataMessage: Boolean = false,
        data: Map<String, String> = emptyMap(),
    ) {
        notificationRepository.save(
            Notification(userId = null, title = title, message = body, type = type, deeplink = urlScheme),
        )
        pushClient.sendTopicMessage(
            TopicPushMessage(title, body, urlScheme, GLOBAL_TOPIC, isUrgentOnAndroid, shouldSendAsDataMessage, data),
        )
    }

    @Transactional
    fun sendPushAndNotification(
        userIds: Collection<Long>,
        title: String,
        body: String,
        type: NotificationType,
        preferenceType: PushPreferenceType,
        urlScheme: String? = null,
        isUrgentOnAndroid: Boolean = false,
        shouldSendAsDataMessage: Boolean = false,
        data: Map<String, String> = emptyMap(),
    ) {
        if (userIds.isEmpty()) return
        notificationRepository.saveAll(
            userIds.map { userId -> Notification(userId = userId, title = title, message = body, type = type, deeplink = urlScheme) },
        )
        val messages =
            resolveMessages(
                userIds.associateWith {
                    TargetedPush(title, body, urlScheme, isUrgentOnAndroid, shouldSendAsDataMessage, data)
                },
                preferenceType,
            )
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = deliver(messages)
            },
        )
    }

    private fun deliver(messages: List<TargetedPushMessage>) {
        if (messages.isEmpty()) return
        val result = pushClient.sendMessages(messages)
        deviceService.markDeletedByRegistrationIds(result.invalidRegistrationIds)
    }
}
