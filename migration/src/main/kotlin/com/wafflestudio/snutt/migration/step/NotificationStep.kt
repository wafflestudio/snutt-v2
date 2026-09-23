package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
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
) : AbstractMigrationStep(jdbc, context) {
    override val name = "notification"
    override val tables = listOf("notification")

    override fun run() {
        val ids = IdSequence()
        var skipped = 0L
        writer(
            "notification",
            listOf("id", "user_id", "title", "message", "type", "deeplink", "created_at", "updated_at"),
        ).use { out ->
            mongo.each("notifications") { doc ->
                val ownerExternalId = doc.oid("user_id")
                val userId = ownerExternalId?.let(context.userIds::get)
                if (ownerExternalId != null && userId == null) {
                    skipped++
                    context.resolved(MigrationSupport.ResolutionReasons.NOTIFICATION_USER_MISSING)
                    return@each
                }
                val createdAt = doc.instant("created_at").orNow().toSqlTimestamp()
                out.add(
                    ids.next(),
                    userId,
                    doc.requireStr("title"),
                    doc.requireStr("message"),
                    TYPE_NAMES.getValue(doc.requireInt("type")),
                    rewriteDeeplink(doc.str("deeplink") ?: doc.str("urlScheme")),
                    createdAt,
                    createdAt,
                )
            }
        }
        alignAutoIncrement("notification", ids.peek())
        log.info("알림 이관: {}건 (사용자가 없어 제외 {}건)", ids.peek() - 1, skipped)
    }

    private fun rewriteDeeplink(value: String?): String? {
        val deeplink = value ?: return null
        if (!OBJECT_ID.containsMatchIn(deeplink)) return deeplink
        val scheme = if (deeplink.startsWith(DEV_SCHEME)) DEV_SCHEME else PROD_SCHEME
        return when {
            deeplink.contains("://timetable-lecture") -> {
                val timetableId = TIMETABLE_ID.find(deeplink)?.let { context.timetableIds[it.groupValues[1]] }
                val lectureId = LECTURE_ID.find(deeplink)?.let { context.lectureIds[it.groupValues[1]] }
                val timetableLectureId =
                    if (timetableId == null || lectureId == null) null else context.timetableLectureIdsByLecture[timetableId to lectureId]
                if (timetableLectureId == null) return unresolvedDeeplink()
                "${scheme}timetable-lecture?timetableId=$timetableId&lectureId=$timetableLectureId"
            }
            deeplink.contains("://bookmarks") -> {
                val lectureId =
                    LECTURE_ID
                        .find(deeplink)
                        ?.groupValues
                        ?.get(1)
                        ?.let(context.lectureIds::get) ?: return unresolvedDeeplink()
                deeplink.replace(LECTURE_ID, "lectureId=$lectureId")
            }
            else -> unresolvedDeeplink()
        }
    }

    private fun unresolvedDeeplink(): String? {
        context.resolved(MigrationSupport.ResolutionReasons.DEEPLINK_TARGET_MISSING)
        return null
    }

    companion object {
        private const val DEV_SCHEME = "snutt-dev://"
        private const val PROD_SCHEME = "snutt://"
        private val OBJECT_ID = Regex("[0-9a-fA-F]{24}")
        private val TIMETABLE_ID = Regex("timetableId=([0-9a-fA-F]{24})")
        private val LECTURE_ID = Regex("lectureId=([0-9a-fA-F]{24})")
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
