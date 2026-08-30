package com.wafflestudio.snutt.core.domain.notification.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.common.pagination.toCursorPage
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class NotificationCursor(
    val createdAt: Instant,
    val notificationId: Long,
)

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun getNotifications(
        userId: Long,
        cursor: String?,
        limit: Int,
        explicit: Boolean,
    ): CursorPage<Notification> {
        if (limit <= 0) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val user = userRepository.findByIdOrNull(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val decoded =
            CursorCodec.decode<NotificationCursor>(cursor)?.also {
                if (it.notificationId <= 0) throw SnuttException(ErrorType.INVALID_CURSOR)
            }
        val results =
            notificationRepository.findNotifications(
                userId = userId,
                registeredAt = checkNotNull(user.createdAt),
                cursorCreatedAt = decoded?.createdAt,
                cursorId = decoded?.notificationId,
                limit = limit + 1,
            )
        if (explicit) {
            user.notificationCheckedAt = Instant.now()
            userRepository.save(user)
        }
        return results.toCursorPage(
            limit,
            cursorOf = { NotificationCursor(checkNotNull(it.createdAt), it.id!!) },
            transform = { it },
        )
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
