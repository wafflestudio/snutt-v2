package com.wafflestudio.snutt.core.domain.notification.repository

import com.wafflestudio.snutt.core.domain.notification.model.Notification
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

interface NotificationBatchRepository {
    fun insertAll(notifications: List<Notification>)
}

class NotificationBatchRepositoryImpl(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) : NotificationBatchRepository {
    override fun insertAll(notifications: List<Notification>) {
        if (notifications.isEmpty()) return
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO notification (user_id, title, title_en, message, message_en, type, deeplink, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            notifications,
            BATCH_SIZE,
        ) { ps, notification ->
            ps.setObject(1, notification.userId)
            ps.setString(2, notification.title)
            ps.setString(3, notification.titleEn)
            ps.setString(4, notification.message)
            ps.setString(5, notification.messageEn)
            ps.setString(6, notification.type.name)
            ps.setString(7, notification.deeplink)
            ps.setObject(8, now)
            ps.setObject(9, now)
        }
    }

    private companion object {
        const val BATCH_SIZE = 1_000
    }
}
