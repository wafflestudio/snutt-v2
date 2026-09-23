package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.oids
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.requireInt
import com.wafflestudio.snutt.migration.requireStr
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
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
            "vacancy_notification",
            "user_device",
            "push_preference",
            "friend",
            "diary_submission",
            "diary_submission_daily_class_type",
            "diary_submission_answer",
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
        var lectureCount = 0L
        writer(
            "bookmark_lecture",
            listOf("id", "user_id", "year", "semester", "lecture_id", "created_at", "updated_at"),
        ).use { out ->
            mongo.each("bookmarks") { doc ->
                val lectureRefs = doc.docs("lectures").map { it.id() }.distinct()
                val userId = context.userIds[doc.oid("user_id")]
                if (userId == null) {
                    context.resolved(MigrationSupport.ResolutionReasons.BOOKMARK_USER_MISSING, lectureRefs.size)
                    return@each
                }
                val lectureIds = lectureRefs.mapNotNull(context.lectureIds::get)
                context.resolved(MigrationSupport.ResolutionReasons.BOOKMARK_LECTURE_MISSING, lectureRefs.size - lectureIds.size)
                val distinctLectureIds = lectureIds.distinct()
                context.resolved(MigrationSupport.ResolutionReasons.BOOKMARK_LECTURE_MERGED, lectureIds.size - distinctLectureIds.size)
                val now = Instant.now().toSqlTimestamp()
                distinctLectureIds.forEach { lectureId ->
                    out.add(ids.next(), userId, doc.requireInt("year"), doc.requireInt("semester"), lectureId, now, now)
                    lectureCount++
                }
            }
        }
        alignAutoIncrement("bookmark_lecture", ids.peek())
        log.info("북마크 이관: 담긴 강의 {}건", lectureCount)
    }

    private fun migrateVacancyNotifications() {
        val ids = IdSequence()
        val seen = HashSet<String>()
        writer(
            "vacancy_notification",
            listOf("id", "user_id", "lecture_id", "created_at", "updated_at"),
        ).use { out ->
            mongo.each("vacancy_notifications") { doc ->
                val userId = context.userIds[doc.oid("userId")]
                if (userId == null) {
                    context.resolved(MigrationSupport.ResolutionReasons.VACANCY_USER_MISSING)
                    return@each
                }
                val lectureId = context.lectureIds[doc.oid("lectureId")]
                if (lectureId == null) {
                    context.resolved(MigrationSupport.ResolutionReasons.VACANCY_LECTURE_MISSING)
                    return@each
                }
                if (!seen.add("$userId\u0000$lectureId")) {
                    context.resolved(MigrationSupport.ResolutionReasons.VACANCY_DUPLICATE)
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
        val devices = mutableListOf<Document>()
        mongo.each("userDevice", devices::add)
        val activeOwnerByRegistrationId =
            devices.indices
                .filter { index ->
                    val doc = devices[index]
                    context.userIds[doc.oid("userId")] != null && !doc.bool("isDeleted")
                }.groupBy { devices[it].requireStr("fcmRegistrationId") }
                .mapValues { (_, indexes) ->
                    indexes.maxWith(compareBy<Int> { devices[it].instant("updatedAt") ?: Instant.EPOCH }.thenBy { it })
                }

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
            devices.forEachIndexed { index, doc ->
                val userId = context.userIds[doc.oid("userId")]
                if (userId == null) {
                    context.resolved(MigrationSupport.ResolutionReasons.DEVICE_USER_MISSING)
                    return@forEachIndexed
                }
                val registrationId = doc.requireStr("fcmRegistrationId")
                val duplicateActiveRegistrationId =
                    !doc.bool("isDeleted") && activeOwnerByRegistrationId[registrationId] != index
                val missingRegistrationId = !doc.bool("isDeleted") && registrationId.isBlank()
                if (duplicateActiveRegistrationId) context.resolved(MigrationSupport.ResolutionReasons.DEVICE_REGISTRATION_DUPLICATE)
                if (missingRegistrationId) context.resolved(MigrationSupport.ResolutionReasons.DEVICE_REGISTRATION_MISSING)
                out.add(
                    ids.next(),
                    userId,
                    doc.str("osType"),
                    doc.str("osVersion"),
                    doc.str("deviceId"),
                    doc.str("deviceModel"),
                    doc.str("appType"),
                    doc.str("appVersion"),
                    registrationId,
                    doc.bool("isDeleted") || duplicateActiveRegistrationId || missingRegistrationId,
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
                val userId = context.userIds[doc.oid("userId")]
                val preferences = doc.docs("pushPreferences")
                if (userId == null) {
                    context.resolved(MigrationSupport.ResolutionReasons.PUSH_PREFERENCE_USER_MISSING, preferences.size)
                    return@each
                }
                val now = Instant.now().toSqlTimestamp()
                preferences.forEach { preference ->
                    out.add(ids.next(), userId, preference.requireStr("type"), preference.bool("isEnabled"), now, now)
                }
            }
        }
        alignAutoIncrement("push_preference", ids.peek())
        log.info("푸시 설정 이관: {}건", ids.peek() - 1)
    }

    private fun migrateFriends() {
        val winners = LinkedHashMap<String, org.bson.Document>()
        mongo.each("friend") { doc ->
            val fromUserId = context.userIds[doc.oid("fromUserId")]
            val toUserId = context.userIds[doc.oid("toUserId")]
            if (fromUserId == null || toUserId == null) {
                context.resolved(MigrationSupport.ResolutionReasons.FRIEND_USER_MISSING)
                return@each
            }
            val key = "${minOf(fromUserId, toUserId)}\u0000${maxOf(fromUserId, toUserId)}"
            val previous = winners[key]
            if (previous == null) {
                winners[key] = doc
                return@each
            }
            context.resolved(MigrationSupport.ResolutionReasons.FRIEND_DUPLICATE)
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
        val dctIds = IdSequence()
        val answerIds = IdSequence()
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
                "created_at",
                "updated_at",
            ),
        ).use { out ->
            writer(
                "diary_submission_daily_class_type",
                listOf("id", "submission_id", "daily_class_type_id", "created_at", "updated_at"),
                parent = out,
            ).use { dctOut ->
                writer(
                    "diary_submission_answer",
                    listOf("id", "submission_id", "question_id", "answer_index", "created_at", "updated_at"),
                    parent = out,
                ).use { answerOut ->
                    mongo.each("diarySubmission") { doc ->
                        val userId = context.userIds[doc.oid("userId")]
                        if (userId == null) {
                            context.resolved(MigrationSupport.ResolutionReasons.DIARY_USER_MISSING)
                            return@each
                        }
                        val createdAt = doc.instant("createdAt").orNow().toSqlTimestamp()
                        val submissionId = ids.next()
                        out.add(
                            submissionId,
                            userId,
                            doc.requireInt("year"),
                            doc.requireInt("semester"),
                            doc.oid("lectureId")?.let(context.lectureIds::get),
                            doc.requireStr("courseTitle"),
                            doc.str("comment"),
                            createdAt,
                            createdAt,
                        )
                        doc.oids("dailyClassTypeIds").forEach { typeId ->
                            dctOut.add(dctIds.next(), submissionId, context.diaryClassTypeIds.getValue(typeId), createdAt, createdAt)
                        }
                        doc.docs("questionAnswers").forEach { answer ->
                            val questionId = context.diaryQuestionIds.getValue(answer.requireStr("questionId"))
                            answerOut.add(
                                answerIds.next(),
                                submissionId,
                                questionId,
                                answer.requireInt("answerIndex"),
                                createdAt,
                                createdAt,
                            )
                        }
                    }
                }
            }
        }
        alignAutoIncrement("diary_submission", ids.peek())
        alignAutoIncrement("diary_submission_daily_class_type", dctIds.peek())
        alignAutoIncrement("diary_submission_answer", answerIds.peek())
        log.info("강의 일기장 기록 이관: {}건", ids.peek() - 1)
    }
}
