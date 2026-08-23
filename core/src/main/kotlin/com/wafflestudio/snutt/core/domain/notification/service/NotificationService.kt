package com.wafflestudio.snutt.core.domain.notification.service

import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
) {
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
                offset = offset,
                limit = limit,
            )
        if (explicit) {
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
}
