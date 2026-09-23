package com.wafflestudio.snutt.migration.step

import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.EvSource
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MigrationSupport.ResolutionReasons
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.str
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat

@Component
class AggregateStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "aggregate"
    override val tables = emptyList<String>()

    override fun run() {
        jdbc.update(
            """
            UPDATE course c JOIN (
                SELECT course_id, id AS lecture_id, course_title, ROW_NUMBER() OVER (
                    PARTITION BY course_id ORDER BY year DESC, semester DESC, updated_at DESC, id DESC
                ) AS rank_index FROM lecture WHERE course_id IS NOT NULL
            ) latest ON latest.course_id=c.id AND latest.rank_index=1
            SET c.title=latest.course_title, c.latest_lecture_id=latest.lecture_id
            """.trimIndent(),
        )
        val updated =
            jdbc.update(
                """
                UPDATE course c
                LEFT JOIN (
                    SELECT course_id, COUNT(*) AS cnt, AVG(rating) AS avg_rating,
                           AVG(grade_satisfaction) AS avg_grade_satisfaction,
                           AVG(teaching_skill) AS avg_teaching_skill,
                           AVG(gains) AS avg_gains,
                           AVG(life_balance) AS avg_life_balance
                    FROM evaluation WHERE is_hidden = FALSE GROUP BY course_id
                ) e ON e.course_id = c.id
                SET c.eval_count = COALESCE(e.cnt, 0),
                    c.avg_rating = e.avg_rating,
                    c.avg_grade_satisfaction = e.avg_grade_satisfaction,
                    c.avg_teaching_skill = e.avg_teaching_skill,
                    c.avg_gains = e.avg_gains,
                    c.avg_life_balance = e.avg_life_balance
                """.trimIndent(),
            )
        log.info("course 집계 갱신: {}건", updated)
    }
}

@Component
class LegacyTokenStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "legacytoken"
    override val tables = emptyList<String>()

    override fun run() {
        jdbc.execute("DELETE FROM legacy_access_token")

        val owners = HashMap<String, MutableList<String>>()
        mongo.each("users") { doc ->
            val hash = legacyTokenHash(doc) ?: return@each
            owners.getOrPut(hash) { mutableListOf() } += doc.id()
        }

        val ids = IdSequence()
        var count = 0L
        var ambiguous = 0L
        writer("legacy_access_token", listOf("id", "user_id", "token_hash", "created_at", "updated_at")).use { out ->
            owners.forEach { (token, externalIds) ->
                if (externalIds.size > 1) {
                    ambiguous++
                    repeat(externalIds.size) { context.resolved(MigrationSupport.ResolutionReasons.LEGACY_TOKEN_AMBIGUOUS) }
                    return@forEach
                }
                val now = Timestamp.from(Instant.now())
                out.add(ids.next(), context.userIds.getValue(externalIds.single()), sha256Hex(token), now, now)
                count++
            }
        }
        alignAutoIncrement("legacy_access_token", ids.peek())
        log.info("구 토큰 이관: {}건 (특정 불가로 제외 {}건)", count, ambiguous)
    }

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    companion object {
        private val CREDENTIAL_KEYS = listOf("localId", "fbId", "appleSub", "googleSub", "kakaoSub")

        fun legacyTokenHash(doc: Document): String? {
            if (!doc.bool("active")) return null
            val credential = doc.doc("credential") ?: return null
            if (CREDENTIAL_KEYS.none { credential.str(it) != null }) return null
            return doc.str("credentialHash")?.takeIf { it.isNotBlank() }
        }
    }
}

@Component
class ValidateStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
    private val ev: EvSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "validate"
    override val tables = emptyList<String>()

    override fun run() {
        val failures = mutableListOf<String>()

        compare(failures, "user", mongo.count("users"), count("user"))
        compare(
            failures,
            "user_social_auth",
            socialCredentials(),
            count("user_social_auth"),
            tolerated = resolved(ResolutionReasons.SOCIAL_AUTH_DUPLICATE),
        )
        compare(
            failures,
            "legacy_access_token",
            legacyTokenOwners(),
            count("legacy_access_token"),
            tolerated = resolved(ResolutionReasons.LEGACY_TOKEN_AMBIGUOUS),
        )
        compare(
            failures,
            "timetable_theme",
            mongo.count("timetableTheme", Filters.eq("isCustom", true)),
            count("timetable_theme", "builtin_code IS NULL"),
            tolerated = resolved(ResolutionReasons.THEME_USER_MISSING, ResolutionReasons.THEME_DOWNLOAD_MERGED),
        )
        compare(
            failures,
            "published_theme",
            mongo.count(
                "timetableTheme",
                Filters.and(
                    Filters.eq("isCustom", true),
                    Filters.ne("status", "DOWNLOADED"),
                    Filters.ne("publishInfo.publishName", null),
                ),
            ),
            count("published_theme"),
            tolerated = resolved(ResolutionReasons.PUBLISHED_THEME_USER_MISSING),
            added = resolved(ResolutionReasons.PUBLISHED_THEME_ARCHIVED),
        )
        compare(
            failures,
            "timetable",
            mongo.count("timetables"),
            count("timetable"),
            tolerated = resolved(ResolutionReasons.TIMETABLE_USER_MISSING),
        )
        compare(
            failures,
            "timetable_lecture",
            timetableLectureEntries(),
            count("timetable_lecture"),
            tolerated = resolved(ResolutionReasons.TIMETABLE_LECTURE_USER_MISSING),
        )
        compare(
            failures,
            "timetable_lecture_reminder",
            mongo.count("timetableLectureReminder"),
            count("timetable_lecture_reminder"),
            tolerated = resolved(ResolutionReasons.REMINDER_TIMETABLE_LECTURE_MISSING),
        )
        compare(
            failures,
            "bookmark_lecture",
            bookmarkLectureEntries(),
            count("bookmark_lecture"),
            tolerated =
                resolved(
                    ResolutionReasons.BOOKMARK_USER_MISSING,
                    ResolutionReasons.BOOKMARK_LECTURE_MISSING,
                    ResolutionReasons.BOOKMARK_LECTURE_MERGED,
                ),
        )
        compare(
            failures,
            "vacancy_notification",
            mongo.count("vacancy_notifications"),
            count("vacancy_notification"),
            tolerated =
                resolved(
                    ResolutionReasons.VACANCY_USER_MISSING,
                    ResolutionReasons.VACANCY_LECTURE_MISSING,
                    ResolutionReasons.VACANCY_DUPLICATE,
                ),
        )
        compare(
            failures,
            "user_device",
            mongo.count("userDevice"),
            count("user_device"),
            tolerated = resolved(ResolutionReasons.DEVICE_USER_MISSING),
        )
        compare(
            failures,
            "push_preference",
            pushPreferenceEntries(),
            count("push_preference"),
            tolerated = resolved(ResolutionReasons.PUSH_PREFERENCE_USER_MISSING),
        )
        compare(
            failures,
            "friend",
            mongo.count("friend"),
            count("friend"),
            tolerated = resolved(ResolutionReasons.FRIEND_USER_MISSING, ResolutionReasons.FRIEND_DUPLICATE),
        )
        compare(
            failures,
            "diary_submission",
            mongo.count("diarySubmission"),
            count("diary_submission"),
            tolerated = resolved(ResolutionReasons.DIARY_USER_MISSING),
        )
        compare(
            failures,
            "notification",
            mongo.count("notifications"),
            count("notification"),
            tolerated = resolved(ResolutionReasons.NOTIFICATION_USER_MISSING),
        )
        compare(failures, "evaluation", evCount("lecture_evaluation"), count("evaluation"))
        compare(
            failures,
            "evaluation_like",
            evCount("evaluation_like"),
            count("evaluation_like"),
            tolerated = resolved(ResolutionReasons.EVALUATION_LIKE_USER_MISSING),
        )
        compare(
            failures,
            "evaluation_report",
            evCount("evaluation_report"),
            count("evaluation_report"),
            tolerated = resolved(ResolutionReasons.EVALUATION_REPORT_USER_MISSING),
        )

        val leakedObjectIds =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE deeplink REGEXP '[0-9a-fA-F]{24}'",
                Long::class.java,
            ) ?: 0L
        if (leakedObjectIds > 0L) failures += "알림 deeplink에 구 ObjectId가 남아 있다: ${leakedObjectIds}건"

        if (context.resolutions.isNotEmpty()) {
            log.info("원본 정리 요약:")
            context.resolutions.forEach { (reason, count) -> log.info("  - {}: {}건", reason, count) }
        }
        check(failures.isEmpty()) { "검증 실패:\n" + failures.joinToString("\n") { "  - $it" } }
        log.info("검증 통과")
    }

    private fun count(
        table: String,
        condition: String = "TRUE",
    ): Long = jdbc.queryForObject("SELECT COUNT(*) FROM `$table` WHERE $condition", Long::class.java)!!

    private fun evCount(table: String): Long = ev.jdbc.queryForObject("SELECT COUNT(*) FROM `$table`", Long::class.java)!!

    private fun resolved(vararg reasons: String): Long = reasons.sumOf { context.resolutions[it] ?: 0L }

    private fun socialCredentials(): Long {
        var total = 0L
        mongo.each("users") { doc ->
            if (!doc.bool("active")) return@each
            val credential = doc.doc("credential") ?: return@each
            total += SOCIAL_KEYS.count { credential.str(it) != null }
        }
        return total
    }

    private fun legacyTokenOwners(): Long {
        var total = 0L
        mongo.each("users") { doc -> if (LegacyTokenStep.legacyTokenHash(doc) != null) total++ }
        return total
    }

    private fun timetableLectureEntries(): Long =
        mongo
            .collection("timetables")
            .aggregate(
                listOf(
                    Aggregates.group(
                        null,
                        Accumulators.sum("count", Document("\$size", Document("\$ifNull", listOf("\$lecture_list", emptyList<Any>())))),
                    ),
                ),
            ).first()
            ?.let { (it["count"] as Number).toLong() } ?: 0L

    private fun bookmarkLectureEntries(): Long {
        var total = 0L
        mongo.each("bookmarks") { doc -> total += doc.docs("lectures").distinctBy { it.id() }.size }
        return total
    }

    private fun pushPreferenceEntries(): Long {
        var total = 0L
        mongo.each("pushPreference") { doc -> total += doc.docs("pushPreferences").size }
        return total
    }

    private fun compare(
        failures: MutableList<String>,
        label: String,
        expected: Long,
        actual: Long,
        tolerated: Long = 0L,
        added: Long = 0L,
    ) {
        if (actual + tolerated != expected + added) {
            failures += "$label 행 수가 어긋난다: 원본 $expected, 대상 $actual (제외 $tolerated, 추가 $added)"
        } else {
            log.info("{} 행 수: 원본 {}, 대상 {} (제외 {}, 추가 {})", label, expected, actual, tolerated, added)
        }
    }

    companion object {
        private val SOCIAL_KEYS = listOf("fbId", "appleSub", "googleSub", "kakaoSub")
    }
}
