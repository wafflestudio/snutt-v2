package com.wafflestudio.snutt.core.domain.notification.repository

import com.wafflestudio.snutt.core.domain.notification.model.Notification
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

interface NotificationBatchRepository {
    fun insertAll(notifications: List<Notification>)
}

class NotificationBatchRepositoryImpl(
    private val jdbcTemplate: JdbcTemplate,
) : NotificationBatchRepository {
    override fun insertAll(notifications: List<Notification>) {
        if (notifications.isEmpty()) return
        val now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
        notifications.chunked(INSERT_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "(?, ?, ?, ?, ?, ?, ?, ?, ?)" }
            jdbcTemplate.update(
                "INSERT INTO notification " +
                    "(user_id, title, title_en, message, message_en, type, deeplink, created_at, updated_at) " +
                    "VALUES $placeholders",
            ) { ps ->
                var idx = 1
                for (n in chunk) {
                    ps.setObject(idx++, n.userId)
                    ps.setString(idx++, n.title)
                    ps.setString(idx++, n.titleEn)
                    ps.setString(idx++, n.message)
                    ps.setString(idx++, n.messageEn)
                    ps.setString(idx++, n.type.name)
                    ps.setString(idx++, n.deeplink)
                    ps.setObject(idx++, now)
                    ps.setObject(idx++, now)
                }
            }
        }
    }

    private companion object {
        const val INSERT_CHUNK_SIZE = 500
    }
}
