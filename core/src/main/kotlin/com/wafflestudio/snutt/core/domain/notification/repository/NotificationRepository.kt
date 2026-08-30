package com.wafflestudio.snutt.core.domain.notification.repository

import com.wafflestudio.snutt.core.domain.notification.model.Notification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface NotificationRepository : JpaRepository<Notification, Long> {
    @Query(
        value =
            "SELECT * FROM notification n WHERE (n.user_id = :userId OR n.user_id IS NULL) " +
                "AND n.created_at > :registeredAt " +
                "AND (:cursorCreatedAt IS NULL OR n.created_at < :cursorCreatedAt " +
                "OR (n.created_at = :cursorCreatedAt AND n.id < :cursorId)) " +
                "ORDER BY n.created_at DESC, n.id DESC LIMIT :limit",
        nativeQuery = true,
    )
    fun findNotifications(
        userId: Long,
        registeredAt: Instant,
        cursorCreatedAt: Instant?,
        cursorId: Long?,
        limit: Int,
    ): List<Notification>

    @Query(
        "SELECT COUNT(n) FROM Notification n WHERE (n.userId = :userId OR n.userId IS NULL) AND n.createdAt > :checkedAt",
    )
    fun countUnread(
        userId: Long,
        checkedAt: Instant,
    ): Long
}
