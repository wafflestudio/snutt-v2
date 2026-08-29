package com.wafflestudio.snutt.core.domain.notification.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
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
        user: User,
        cursor: String?,
        limit: Int,
        explicit: Boolean,
    ): CursorPage<Notification> {
        if (limit <= 0) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val decoded =
            CursorCodec.decode<NotificationCursor>(cursor)?.also {
                if (it.notificationId <= 0) throw SnuttException(ErrorType.INVALID_CURSOR)
            }
        val results =
            notificationRepository.findNotifications(
                userId = user.id!!,
                registeredAt = checkNotNull(user.createdAt),
                cursorCreatedAt = decoded?.createdAt,
                cursorId = decoded?.notificationId,
                limit = limit + 1,
            )
        if (explicit) {
            user.notificationCheckedAt = Instant.now()
            userRepository.save(user)
        }
        val hasMore = results.size > limit
        val content = if (hasMore) results.take(limit) else results
        val nextCursor =
            if (hasMore) {
                content.lastOrNull()?.let {
                    CursorCodec.encode(NotificationCursor(checkNotNull(it.createdAt), it.id!!))
                }
            } else {
                null
            }
        return CursorPage.of(content, nextCursor, limit)
    }

    fun getUnreadCount(user: User): Long = notificationRepository.countUnread(user.id!!, user.notificationCheckedAt)

    @Transactional
    fun sendNotification(notification: Notification) {
        notificationRepository.save(notification)
    }
}
