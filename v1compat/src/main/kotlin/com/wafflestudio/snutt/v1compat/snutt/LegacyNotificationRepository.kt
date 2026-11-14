package com.wafflestudio.snutt.v1compat.snutt

import com.wafflestudio.snutt.core.domain.notification.model.Notification
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant

interface LegacyNotificationRepository : Repository<Notification, Long> {
    @Query(
        value = """
            SELECT * FROM (
                (SELECT * FROM notification n WHERE n.user_id = :userId AND n.created_at > :registeredAt
                 ORDER BY n.created_at DESC, n.id DESC LIMIT :fetchLimit)
                UNION ALL
                (SELECT * FROM notification n WHERE n.user_id IS NULL AND n.created_at > :registeredAt
                 ORDER BY n.created_at DESC, n.id DESC LIMIT :fetchLimit)
            ) n ORDER BY n.created_at DESC, n.id DESC LIMIT :limit OFFSET :offset
        """,
        nativeQuery = true,
    )
    fun findNotificationsWithOffset(
        userId: Long,
        registeredAt: Instant,
        fetchLimit: Int,
        limit: Int,
        offset: Int,
    ): List<Notification>

    @Modifying
    @Query("UPDATE User u SET u.notificationCheckedAt = :checkedAt WHERE u.id = :userId")
    fun markChecked(
        userId: Long,
        checkedAt: Instant,
    )
}
