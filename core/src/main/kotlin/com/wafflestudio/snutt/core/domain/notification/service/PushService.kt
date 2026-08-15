package com.wafflestudio.snutt.core.domain.notification.service

import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.push.TargetedPushMessage
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// push_preference 필터 + 기기 FCM 발송 + 알림함 저장 (v1 PushWithNotificationService 이식)
data class TargetedPush(
    val title: String,
    val body: String,
    val urlScheme: String? = null,
)

@Service
class PushService(
    private val pushClient: PushClient,
    private val userDeviceRepository: UserDeviceRepository,
    private val pushPreferenceRepository: PushPreferenceRepository,
    private val notificationRepository: NotificationRepository,
) {
    /** 사용자별 개별 메시지를 FCM으로만 보낸다. 알림함에는 남지 않는다 (v1 sendTargetPushes) */
    @Transactional(readOnly = true)
    fun sendTargetedPushes(
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
        pushClient.sendMessages(
            devices.mapNotNull { device ->
                targets[device.user.id]?.let {
                    TargetedPushMessage(it.title, it.body, it.urlScheme, device.fcmRegistrationId)
                }
            },
        )
    }

    @Transactional
    fun sendGlobalPushAndNotification(
        title: String,
        body: String,
        type: NotificationType,
        urlScheme: String? = null,
    ) {
        val devices = userDeviceRepository.findAllByIsDeletedFalse()
        pushClient.sendMessages(
            devices.map { TargetedPushMessage(title, body, urlScheme, it.fcmRegistrationId) },
        )
        notificationRepository.save(
            Notification(userId = null, title = title, message = body, type = type, deeplink = urlScheme),
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
    ) {
        if (userIds.isEmpty()) return
        // 푸시 설정은 FCM 발송만 거른다. 알림함에는 항상 남는다 (v1 PushWithNotificationService 동일)
        val disabledUserIds =
            pushPreferenceRepository
                .findByUserIdInAndTypeAndIsEnabledFalse(userIds, preferenceType)
                .map { it.user.id!! }
                .toSet()
        val pushTargets = userIds.filter { it !in disabledUserIds }

        val devices = userDeviceRepository.findAllByUserIdInAndIsDeletedFalse(pushTargets)
        pushClient.sendMessages(
            devices.map { device ->
                TargetedPushMessage(
                    title = title,
                    body = body,
                    urlScheme = urlScheme,
                    fcmRegistrationId = device.fcmRegistrationId,
                )
            },
        )
        notificationRepository.saveAll(
            userIds.map { userId -> Notification(userId = userId, title = title, message = body, type = type, deeplink = urlScheme) },
        )
    }
}
