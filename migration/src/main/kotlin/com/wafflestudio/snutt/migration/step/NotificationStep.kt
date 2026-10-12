package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationStep
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
) : MigrationStep(jdbc, context) {
    override val tables = listOf("notification")

    override fun run() {
        val ids = IdSequence()
        writer(
            "notification",
            listOf("id", "user_id", "title", "message", "type", "deeplink", "created_at", "updated_at"),
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
                    userId,
                    doc.requireStr("title"),
                    doc.requireStr("message"),
                    NotificationType.fromValue(doc.requireInt("type")).name,
                    rewriteDeeplink(doc.str("deeplink") ?: doc.str("urlScheme")),
                    createdAt,
                    createdAt,
                )
            }
        }
        log.info("알림 이관: {}건", ids.peek() - 1)
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
    }
}
