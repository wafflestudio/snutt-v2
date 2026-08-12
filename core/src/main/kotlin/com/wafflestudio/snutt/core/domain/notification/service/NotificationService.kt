package com.wafflestudio.snutt.core.domain.notification.service

import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
) {
    // explicit=true면 조회 시점을 읽음 처리한다 (v1 동일)
    @Transactional
    fun getNotifications(
        user: User,
        offset: Long,
        limit: Int,
        explicit: Boolean,
    ): List<Notification> {
        val notifications =
            notificationRepository.findNotifications(
                userId = user.id!!,
                registeredAt = checkNotNull(user.createdAt),
                pageable = PageRequest.of((offset / limit).toInt(), limit),
            )
        if (explicit) {
            // 인터셉터가 로드한 detached user이므로 명시적으로 저장한다
            user.notificationCheckedAt = Instant.now()
            userRepository.save(user)
        }
        return notifications
    }

    fun getUnreadCount(user: User): Long = notificationRepository.countUnread(user.id!!, user.notificationCheckedAt)

    @Transactional
    fun sendNotification(notification: Notification) {
        notificationRepository.save(notification)
    }

    @Transactional
    fun sendNotifications(notifications: List<Notification>) {
        notificationRepository.saveAll(notifications)
    }
}
