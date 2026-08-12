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
@Service
class PushService(
    private val pushClient: PushClient,
    private val userDeviceRepository: UserDeviceRepository,
    private val pushPreferenceRepository: PushPreferenceRepository,
    private val notificationRepository: NotificationRepository,
) {
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
        // 알림을 원하지 않는 사용자는 제외한다 (기본값은 수신)
        val disabledUserIds =
            pushPreferenceRepository
                .findByUserIdInAndTypeAndIsEnabledFalse(userIds, preferenceType)
                .map { it.user.id!! }
                .toSet()
        val targets = userIds.filter { it !in disabledUserIds }
        if (targets.isEmpty()) return

        val devices = userDeviceRepository.findAllByUserIdInAndIsDeletedFalse(targets)
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
            targets.map { userId -> Notification(userId = userId, title = title, message = body, type = type, deeplink = urlScheme) },
        )
    }
}
