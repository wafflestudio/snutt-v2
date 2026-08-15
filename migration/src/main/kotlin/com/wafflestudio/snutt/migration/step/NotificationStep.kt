package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class NotificationStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "notification"
    override val tables = listOf("notification")

    override fun run() {
        val ids = IdSequence()
        var skipped = 0L
        writer(
            "notification",
            listOf("id", "external_id", "user_id", "title", "message", "type", "deeplink", "created_at", "updated_at"),
        ).use { out ->
            mongo.each("notifications") { doc ->
                val ownerExternalId = doc.oid("user_id")
                val userId = ownerExternalId?.let(context.userIds::get)
                if (ownerExternalId != null && userId == null) {
                    skipped++
                    return@each
                }
                val createdAt = doc.instant("created_at").orNow().toSqlTimestamp()
                out.add(
                    ids.next(),
                    doc.id(),
                    userId,
                    doc.str("title").orEmpty(),
                    doc.str("message") ?: doc.str("body").orEmpty(),
                    typeName(doc.int("type")),
                    doc.str("deeplink") ?: doc.str("urlScheme"),
                    createdAt,
                    createdAt,
                )
            }
        }
        alignAutoIncrement("notification", ids.peek())
        log.info("알림 이관: {}건 (사용자가 없어 제외 {}건)", ids.peek() - 1, skipped)
    }

    private fun typeName(value: Int?): String = TYPE_NAMES[value] ?: "NORMAL"

    companion object {
        private val TYPE_NAMES =
            mapOf(
                0 to "NORMAL",
                1 to "COURSEBOOK",
                2 to "LECTURE_UPDATE",
                3 to "LECTURE_REMOVE",
                4 to "LECTURE_VACANCY",
                5 to "FRIEND",
                6 to "FEATURE_NEW",
                7 to "DIARY",
            )
    }
}
