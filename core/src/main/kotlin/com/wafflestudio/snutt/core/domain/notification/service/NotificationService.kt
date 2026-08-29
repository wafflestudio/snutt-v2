package com.wafflestudio.snutt.core.domain.notification.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
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
        userId: Long,
        offset: Long,
        limit: Int,
        explicit: Boolean,
    ): List<Notification> {
        val user = userRepository.findByIdOrNull(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val notifications =
            notificationRepository.findNotifications(
                userId = userId,
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

    fun getUnreadCount(userId: Long): Long {
        val user = userRepository.findByIdOrNull(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        return notificationRepository.countUnread(userId, user.notificationCheckedAt)
    }

    @Transactional
    fun sendNotification(notification: Notification) {
        notificationRepository.save(notification)
    }
}
