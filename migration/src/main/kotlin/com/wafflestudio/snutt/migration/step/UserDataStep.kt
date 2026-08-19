package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.Json
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.oids
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class UserDataStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "userdata"
    override val tables =
        listOf(
            "bookmark_lecture",
            "bookmark",
            "vacancy_notification",
            "user_device",
            "push_preference",
            "friend",
            "diary_submission",
        )

    override fun run() {
        migrateBookmarks()
        migrateVacancyNotifications()
        migrateUserDevices()
        migratePushPreferences()
        migrateFriends()
        migrateDiarySubmissions()
    }

    private fun migrateBookmarks() {
        val ids = IdSequence()
        val lectureIds = IdSequence()
        var lectureCount = 0L
        writer("bookmark", listOf("id", "user_id", "year", "semester", "created_at", "updated_at")).use { out ->
            writer(
                "bookmark_lecture",
                listOf("id", "bookmark_id", "lecture_id", "created_at", "updated_at"),
                parent = out,
            ).use { items ->
                mongo.each("bookmarks") { doc ->
                    val userId = context.userIds[doc.oid("user_id")] ?: return@each
                    val id = ids.next()
                    val now = Instant.now().toSqlTimestamp()
                    out.add(id, userId, doc.int("year") ?: 0, doc.int("semester") ?: 1, now, now)
                    doc
                        .docs("lectures")
                        .mapNotNull { context.lectureIds[it.id()] }
                        .distinct()
                        .forEach { lectureId ->
                            items.add(lectureIds.next(), id, lectureId, now, now)
                            lectureCount++
                        }
                }
            }
        }
        alignAutoIncrement("bookmark", ids.peek())
        alignAutoIncrement("bookmark_lecture", lectureIds.peek())
        log.info("북마크 이관: {}건, 담긴 강의 {}건", ids.peek() - 1, lectureCount)
    }

    private fun migrateVacancyNotifications() {
        val ids = IdSequence()
        val seen = HashSet<String>()
        writer(
            "vacancy_notification",
            listOf("id", "user_id", "lecture_id", "created_at", "updated_at"),
        ).use { out ->
            mongo.each("vacancy_notifications") { doc ->
                val userId = context.userIds[doc.oid("userId")] ?: return@each
                val lectureId = context.lectureIds[doc.oid("lectureId")] ?: return@each
                if (!seen.add("$userId\u0000$lectureId")) {
                    context.resolved("같은 사용자·강의의 빈자리 알림이 중복되어 제외")
                    return@each
                }
                val now = Instant.now().toSqlTimestamp()
                out.add(ids.next(), userId, lectureId, now, now)
            }
        }
        alignAutoIncrement("vacancy_notification", ids.peek())
        log.info("빈자리 알림 이관: {}건", ids.peek() - 1)
    }

    private fun migrateUserDevices() {
        val ids = IdSequence()
        writer(
            "user_device",
            listOf(
                "id",
                "user_id",
                "os_type",
                "os_version",
                "device_id",
                "device_model",
                "app_type",
                "app_version",
                "fcm_registration_id",
                "is_deleted",
                "created_at",
                "updated_at",
            ),
        ).use { out ->
            mongo.each("userDevice") { doc ->
                val userId = context.userIds[doc.oid("userId")] ?: return@each
                out.add(
                    ids.next(),
                    userId,
                    doc.str("osType"),
                    doc.str("osVersion"),
                    doc.str("deviceId"),
                    doc.str("deviceModel"),
                    doc.str("appType"),
                    doc.str("appVersion"),
                    doc.str("fcmRegistrationId").orEmpty(),
                    doc.bool("isDeleted"),
                    doc.instant("createdAt").orNow().toSqlTimestamp(),
                    doc.instant("updatedAt").orNow().toSqlTimestamp(),
                )
            }
        }
        alignAutoIncrement("user_device", ids.peek())
        log.info("기기 이관: {}건", ids.peek() - 1)
    }

    private fun migratePushPreferences() {
        val ids = IdSequence()
        writer("push_preference", listOf("id", "user_id", "type", "is_enabled", "created_at", "updated_at")).use { out ->
            mongo.each("pushPreference") { doc ->
                val userId = context.userIds[doc.oid("userId")] ?: return@each
                val now = Instant.now().toSqlTimestamp()
                doc
                    .docs("pushPreferences")
                    .mapNotNull { preference -> preference.str("type")?.let { it to preference.bool("isEnabled") } }
                    .distinctBy { it.first }
                    .filter { (type, _) -> type in PUSH_PREFERENCE_TYPES }
                    .forEach { (type, enabled) -> out.add(ids.next(), userId, type, enabled, now, now) }
            }
        }
        alignAutoIncrement("push_preference", ids.peek())
        log.info("푸시 설정 이관: {}건", ids.peek() - 1)
    }

    private fun migrateFriends() {
        val winners = LinkedHashMap<String, org.bson.Document>()
        mongo.each("friend") { doc ->
            val fromUserId = context.userIds[doc.oid("fromUserId")] ?: return@each
            val toUserId = context.userIds[doc.oid("toUserId")] ?: return@each
            val key = "${minOf(fromUserId, toUserId)}\u0000${maxOf(fromUserId, toUserId)}"
            val previous = winners[key]
            if (previous == null) {
                winners[key] = doc
                return@each
            }
            context.resolved("같은 사용자 쌍의 친구 관계가 중복되어 하나만 남김")
            val previousWins =
                previous.bool("isAccepted") ||
                    !doc.bool("isAccepted") &&
                    previous.instant("createdAt").orNow() <= doc.instant("createdAt").orNow()
            if (!previousWins) winners[key] = doc
        }

        val ids = IdSequence()
        writer(
            "friend",
            listOf(
                "id",
                "from_user_id",
                "to_user_id",
                "from_display_name",
                "to_display_name",
                "is_accepted",
                "created_at",
                "updated_at",
            ),
        ).use { out ->
            winners.values.forEach { doc ->
                out.add(
                    ids.next(),
                    context.userIds.getValue(doc.oid("fromUserId")!!),
                    context.userIds.getValue(doc.oid("toUserId")!!),
                    doc.str("fromUserDisplayName"),
                    doc.str("toUserDisplayName"),
                    doc.bool("isAccepted"),
                    doc.instant("createdAt").orNow().toSqlTimestamp(),
                    doc.instant("updatedAt").orNow().toSqlTimestamp(),
                )
            }
        }
        alignAutoIncrement("friend", ids.peek())
        log.info("친구 이관: {}건", winners.size)
    }

    private fun migrateDiarySubmissions() {
        val ids = IdSequence()
        writer(
            "diary_submission",
            listOf(
                "id",
                "user_id",
                "year",
                "semester",
                "lecture_id",
                "course_title",
                "comment",
                "daily_class_type_id_list",
                "question_answer_list",
                "created_at",
                "updated_at",
            ),
        ).use { out ->
            mongo.each("diarySubmission") { doc ->
                val userId = context.userIds[doc.oid("userId")] ?: return@each
                val createdAt = doc.instant("createdAt").orNow().toSqlTimestamp()
                val answers =
                    doc.docs("questionAnswers").mapNotNull { answer ->
                        val questionId = answer.str("questionId")?.let(context.diaryQuestionIds::get) ?: return@mapNotNull null
                        mapOf("questionId" to questionId, "answerIndex" to (answer.int("answerIndex") ?: 0))
                    }
                out.add(
                    ids.next(),
                    userId,
                    doc.int("year") ?: 0,
                    doc.int("semester") ?: 1,
                    doc.oid("lectureId")?.let(context.lectureIds::get),
                    doc.str("courseTitle").orEmpty(),
                    doc.str("comment"),
                    Json.writeRequired(doc.oids("dailyClassTypeIds").mapNotNull(context.diaryClassTypeIds::get)),
                    Json.writeRequired(answers),
                    createdAt,
                    createdAt,
                )
            }
        }
        alignAutoIncrement("diary_submission", ids.peek())
        log.info("강의 일기장 기록 이관: {}건", ids.peek() - 1)
    }

    companion object {
        private val PUSH_PREFERENCE_TYPES = setOf("NORMAL", "LECTURE_UPDATE", "VACANCY_NOTIFICATION", "DIARY")
    }
}
