package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationStep
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.requireInt
import com.wafflestudio.snutt.migration.requireStr
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class NotificationStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : MigrationStep(jdbc, context) {
    override val tables = listOf("notification")

    override fun run() {
        val ids = IdSequence()
        writer(
            "notification",
            listOf("id", "idv1", "user_id", "title", "message", "type", "deeplink", "created_at", "updated_at"),
        ).use { out ->
            mongo.each("notifications") { doc ->
                val ownerExternalId = doc.oid("user_id")
                val userId = ownerExternalId?.let(context.userIds::get)
                if (ownerExternalId != null && userId == null) {
                    context.resolved(MigrationSupport.ResolutionReasons.NOTIFICATION_USER_MISSING)
                    return@each
                }
                val createdAt = doc.instant("created_at").orNow().toSqlTimestamp()
                out.add(
                    ids.next(),
                    doc.id(),
                    userId,
                    doc.requireStr("title"),
                    doc.requireStr("message"),
                    NotificationType.fromValue(doc.requireInt("type")).name,
                    withV2Ids(doc.str("deeplink") ?: doc.str("urlScheme")),
                    createdAt,
                    createdAt,
                )
            }
        }
        log.info("알림 이관: {}건", ids.peek() - 1)
    }

    private fun withV2Ids(value: String?): String? {
        if (value == null) return null
        val link = value.substringBefore('#')
        val scheme = link.substringBefore("://", "")
        val target = link.substringAfter("://", "").substringBefore('?')
        if (scheme !in setOf("snutt", "snutt-dev") || target !in setOf("timetable-lecture", "bookmarks", "diary")) return value
        val query = link.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val ids =
            buildMap {
                if (target == "timetable-lecture") {
                    query["timetableId"]?.lowercase()?.let(context.timetableIds::get)?.let { put("timetableIdV2", it) }
                }
                query["lectureId"]?.lowercase()?.let(context.lectureIds::get)?.let { put("lectureIdV2", it) }
            }.filterKeys { it !in query }
        if (ids.isEmpty()) return value
        val separator =
            if (link.endsWith('?') || link.endsWith('&')) {
                ""
            } else if ('?' in link) {
                "&"
            } else {
                "?"
            }
        val fragment = if ('#' in value) "#" + value.substringAfter('#') else ""
        return link + separator + ids.entries.joinToString("&") { (key, id) -> "$key=$id" } + fragment
    }
}
