package com.wafflestudio.snutt.core.domain.notification.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.common.pagination.MAX_PAGE_SIZE
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
    private val cursorCodec: CursorCodec,
) {
    @Transactional
    fun getNotifications(
        userId: Long,
        cursor: String?,
        limit: Int,
        explicit: Boolean,
    ): CursorPage<Notification> {
        if (limit !in 1..MAX_PAGE_SIZE) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val user = userRepository.findByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val decoded =
            cursorCodec.decode<NotificationCursor>(cursor)?.also {
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
        if (explicit) markChecked(user)
        return cursorCodec.pageOf(
            items = results,
            pageSize = limit,
            cursorOf = { NotificationCursor(checkNotNull(it.createdAt), it.id!!) },
            transform = { it },
        )
    }

    @Transactional
    fun getNotificationsByOffset(
        userId: Long,
        offset: Long,
        limit: Int,
        explicit: Boolean,
    ): List<Notification> {
        if (offset < 0 || limit !in 1..MAX_PAGE_SIZE || offset > Int.MAX_VALUE - limit) {
            throw SnuttException(ErrorType.INVALID_PARAMETER)
        }
        val user = userRepository.findByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val results =
            notificationRepository.findNotifications(
                userId = userId,
                registeredAt = checkNotNull(user.createdAt),
                cursorCreatedAt = null,
                cursorId = null,
                limit = (offset + limit).toInt(),
            )
        if (explicit) markChecked(user)
        return results.drop(offset.toInt())
    }

    private fun markChecked(user: User) {
        user.notificationCheckedAt = Instant.now()
        userRepository.save(user)
    }

    fun getUnreadCount(userId: Long): Long {
        val user = userRepository.findByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        return notificationRepository.countUnread(userId, user.notificationCheckedAt)
    }

    @Transactional
    fun sendNotification(notification: Notification) {
        notificationRepository.save(notification)
    }
}
