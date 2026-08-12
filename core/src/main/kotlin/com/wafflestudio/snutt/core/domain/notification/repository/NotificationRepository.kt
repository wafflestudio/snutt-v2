package com.wafflestudio.snutt.core.domain.notification.repository

import com.wafflestudio.snutt.core.domain.notification.model.Notification
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface NotificationRepository : JpaRepository<Notification, Long> {
    // user_id가 나거나 전체 공지 중, 가입일 이후 생성된 알림
    @Query(
        "SELECT n FROM Notification n WHERE (n.userId = :userId OR n.userId IS NULL) " +
            "AND n.createdAt > :registeredAt ORDER BY n.createdAt DESC",
    )
    fun findNotifications(
        userId: Long,
        registeredAt: Instant,
        pageable: Pageable,
    ): List<Notification>

    @Query(
        "SELECT COUNT(n) FROM Notification n WHERE (n.userId = :userId OR n.userId IS NULL) AND n.createdAt > :checkedAt",
    )
    fun countUnread(
        userId: Long,
        checkedAt: Instant,
    ): Long
}
