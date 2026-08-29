package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.EvSource
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.str
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
        val updated =
            jdbc.update(
                """
                UPDATE course c
                LEFT JOIN (
                    SELECT course_id, COUNT(*) AS cnt, AVG(rating) AS avg_rating
                    FROM evaluation WHERE is_hidden = FALSE GROUP BY course_id
                ) e ON e.course_id = c.id
                SET c.eval_count = COALESCE(e.cnt, 0), c.avg_rating = e.avg_rating
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
        if (!tableExists()) {
            log.info("legacy_access_token 테이블이 없다. v1compat 모듈이 없는 배포이므로 건너뛴다")
            return
        }
        jdbc.execute("DELETE FROM legacy_access_token")

        val owners = HashMap<String, String?>()
        mongo.each("users") { doc ->
            if (!doc.bool("active")) return@each
            if (!doc.hasCredential()) return@each
            val hash = doc.str("credentialHash")?.takeIf { it.isNotBlank() } ?: return@each
            owners[hash] = if (owners.containsKey(hash)) null else doc.id()
        }

        val ids = IdSequence()
        var count = 0L
        var ambiguous = 0L
        writer("legacy_access_token", listOf("id", "user_id", "token_hash", "created_at", "updated_at")).use { out ->
            owners.forEach { (token, externalId) ->
                if (externalId == null) {
                    ambiguous++
                    context.resolved("같은 구 토큰을 가진 활성 계정이 여럿이라 토큰을 이관하지 않음")
                    return@forEach
                }
                val userId = context.userIds[externalId] ?: return@forEach
                val now = Timestamp.from(Instant.now())
                out.add(ids.next(), userId, sha256Hex(token), now, now)
                count++
            }
        }
        alignAutoIncrement("legacy_access_token", ids.peek())
        log.info("구 토큰 이관: {}건 (특정 불가로 제외 {}건)", count, ambiguous)
    }

    private fun tableExists(): Boolean =
        (
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'legacy_access_token'",
                Long::class.java,
            ) ?: 0L
        ) > 0L

    private fun org.bson.Document.hasCredential(): Boolean {
        val credential = doc("credential") ?: return false
        return CREDENTIAL_KEYS.any { credential.str(it) != null }
    }

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    companion object {
        private val CREDENTIAL_KEYS = listOf("localId", "fbId", "appleSub", "googleSub", "kakaoSub")
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
        compare(failures, "timetable", mongo.count("timetables"), count("timetable"))
        if (ev.available) {
            compare(
                failures,
                "evaluation",
                ev.jdbc.queryForObject("SELECT COUNT(*) FROM lecture_evaluation", Long::class.java) ?: 0L,
                count("evaluation"),
                tolerated =
                    context.resolutions
                        .filterKeys { it.contains("강의평") }
                        .values
                        .sum(),
            )
        }

        orphans(failures, "timetable", "SELECT COUNT(*) FROM timetable t LEFT JOIN `user` u ON u.id = t.user_id WHERE u.id IS NULL")
        orphans(
            failures,
            "timetable_lecture",
            "SELECT COUNT(*) FROM timetable_lecture tl LEFT JOIN timetable t ON t.id = tl.timetable_id WHERE t.id IS NULL",
        )
        orphans(
            failures,
            "bookmark_lecture",
            "SELECT COUNT(*) FROM bookmark_lecture bl LEFT JOIN lecture l ON l.id = bl.lecture_id WHERE l.id IS NULL",
        )
        orphans(
            failures,
            "evaluation",
            "SELECT COUNT(*) FROM evaluation e LEFT JOIN course c ON c.id = e.course_id WHERE c.id IS NULL",
        )

        val aggregateMismatch =
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM course c
                LEFT JOIN (
                    SELECT course_id, COUNT(*) AS cnt FROM evaluation WHERE is_hidden = FALSE GROUP BY course_id
                ) e ON e.course_id = c.id
                WHERE c.eval_count <> COALESCE(e.cnt, 0)
                """.trimIndent(),
                Long::class.java,
            ) ?: 0L
        if (aggregateMismatch > 0L) failures += "course 집계가 강의평과 어긋난다: ${aggregateMismatch}건"

        if (context.resolutions.isNotEmpty()) {
            log.info("원본 정리 요약:")
            context.resolutions.forEach { (reason, count) -> log.info("  - {}: {}건", reason, count) }
        }
        check(failures.isEmpty()) { "검증 실패:\n" + failures.joinToString("\n") { "  - $it" } }
        log.info("검증 통과")
    }

    private fun count(table: String): Long = jdbc.queryForObject("SELECT COUNT(*) FROM `$table`", Long::class.java) ?: 0L

    private fun compare(
        failures: MutableList<String>,
        label: String,
        expected: Long,
        actual: Long,
        tolerated: Long = 0L,
    ) {
        if (actual + tolerated < expected) {
            failures += "$label 행 수 부족: 원본 $expected, 대상 $actual (허용 $tolerated)"
        } else {
            log.info("{} 행 수: 원본 {}, 대상 {}", label, expected, actual)
        }
    }

    private fun orphans(
        failures: MutableList<String>,
        label: String,
        sql: String,
    ) {
        val count = jdbc.queryForObject(sql, Long::class.java) ?: 0L
        if (count > 0L) failures += "$label 의 고아 참조 ${count}건"
    }
}
