package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.EvSource
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

@Component
class EvaluationStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val ev: EvSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "evaluation"
    override val tables = listOf("evaluation_like", "evaluation_report", "evaluation")

    private class Anchor(
        val courseId: Long,
        val year: Int,
        val semester: Int,
    )

    override fun run() {
        if (!ev.available) {
            log.info("구 ev DB가 없어 강의평 이관을 건너뛴다")
            return
        }
        val anchors = loadAnchors()
        val migrated = migrateEvaluations(anchors)
        migrateLikes(migrated)
        migrateReports(migrated)
    }

    private fun loadAnchors(): Map<Long, Anchor> {
        val anchors = HashMap<Long, Anchor>(256_000)
        ev.jdbc.query("SELECT id, lecture_id, year, semester FROM semester_lecture") { rs ->
            anchors[rs.getLong("id")] =
                Anchor(
                    courseId = rs.getLong("lecture_id"),
                    year = rs.getInt("year"),
                    semester = rs.getInt("semester"),
                )
        }
        return anchors
    }

    private fun migrateEvaluations(anchors: Map<Long, Anchor>): Set<Long> {
        val migrated = HashSet<Long>(64_000)
        val authored = HashMap<String, Long>(64_000)
        var maxId = 0L
        var count = 0L
        writer("evaluation", COLUMNS).use { out ->
            ev.jdbc.query(
                "SELECT id, semester_lecture_id, user_id, content, grade_satisfaction, teaching_skill, gains, " +
                    "life_balance, rating, like_count, is_hidden, is_reported, from_snuev, created_at, updated_at " +
                    "FROM lecture_evaluation ORDER BY id",
            ) { rs ->
                val id = rs.getLong("id")
                val anchor = anchors[rs.getLong("semester_lecture_id")]
                if (anchor == null) {
                    context.resolved("개설을 찾을 수 없는 강의평을 제외")
                    return@query
                }
                val userId = context.userIds[rs.getString("user_id")]
                var hidden = rs.getBoolean("is_hidden")
                if (!hidden && userId != null) {
                    val key = "${anchor.courseId}\u0000${anchor.year}\u0000${anchor.semester}\u0000$userId"
                    val previous = authored.put(key, id)
                    if (previous != null) {
                        out.flush()
                        jdbc.update("UPDATE evaluation SET is_hidden = TRUE WHERE id = ?", previous)
                        context.resolved("한 사용자가 같은 개설에 강의평을 여럿 남겨 이전 것을 숨김")
                    }
                }
                maxId = maxOf(maxId, id)
                migrated.add(id)
                count++
                out.add(
                    id,
                    anchor.courseId,
                    userId,
                    anchor.year,
                    anchor.semester,
                    rs.getString("content").orEmpty(),
                    rs.getObject("grade_satisfaction") as? Double,
                    rs.getObject("teaching_skill") as? Double,
                    rs.getObject("gains") as? Double,
                    rs.getObject("life_balance") as? Double,
                    rs.getDouble("rating"),
                    rs.getLong("like_count"),
                    hidden,
                    rs.getBoolean("is_reported"),
                    rs.getBoolean("from_snuev"),
                    rs.getTimestamp("created_at") ?: Instant.now().toSqlTimestamp(),
                    rs.getTimestamp("updated_at") ?: Instant.now().toSqlTimestamp(),
                )
            }
        }
        alignAutoIncrement("evaluation", maxId + 1)
        log.info("강의평 이관: {}건", count)
        return migrated
    }

    private fun migrateLikes(migrated: Set<Long>) {
        var maxId = 0L
        var count = 0L
        var skipped = 0L
        writer("evaluation_like", listOf("id", "evaluation_id", "user_id", "created_at", "updated_at")).use { out ->
            ev.jdbc.query("SELECT id, lecture_evaluation_id, user_id, created_at, updated_at FROM evaluation_like") { rs ->
                val evaluationId = rs.getLong("lecture_evaluation_id")
                val userId = context.userIds[rs.getString("user_id")]
                if (evaluationId !in migrated || userId == null) {
                    skipped++
                    return@query
                }
                val id = rs.getLong("id")
                maxId = maxOf(maxId, id)
                count++
                out.add(id, evaluationId, userId, rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"))
            }
        }
        alignAutoIncrement("evaluation_like", maxId + 1)
        syncLikeCounts()
        log.info("공감 이관: {}건 (사용자·강의평이 없어 제외 {}건)", count, skipped)
    }

    private fun syncLikeCounts() {
        jdbc.update(
            "UPDATE evaluation e SET e.like_count = " +
                "(SELECT COUNT(*) FROM evaluation_like l WHERE l.evaluation_id = e.id)",
        )
    }

    private fun migrateReports(migrated: Set<Long>) {
        var maxId = 0L
        var count = 0L
        writer(
            "evaluation_report",
            listOf("id", "evaluation_id", "user_id", "content", "is_hidden", "created_at", "updated_at"),
        ).use { out ->
            ev.jdbc.query(
                "SELECT id, lecture_evaluation_id, user_id, content, is_hidden, created_at, updated_at FROM evaluation_report",
            ) { rs ->
                val evaluationId = rs.getLong("lecture_evaluation_id")
                val userId = context.userIds[rs.getString("user_id")] ?: return@query
                if (evaluationId !in migrated) return@query
                val id = rs.getLong("id")
                maxId = maxOf(maxId, id)
                count++
                out.add(
                    id,
                    evaluationId,
                    userId,
                    rs.getString("content").orEmpty(),
                    rs.getBoolean("is_hidden"),
                    rs.getTimestamp("created_at") ?: Timestamp.from(Instant.now()),
                    rs.getTimestamp("updated_at") ?: Timestamp.from(Instant.now()),
                )
            }
        }
        alignAutoIncrement("evaluation_report", maxId + 1)
        log.info("신고 이관: {}건", count)
    }

    companion object {
        private val COLUMNS =
            listOf(
                "id",
                "course_id",
                "user_id",
                "year",
                "semester",
                "content",
                "grade_satisfaction",
                "teaching_skill",
                "gains",
                "life_balance",
                "rating",
                "like_count",
                "is_hidden",
                "is_reported",
                "from_snuev",
                "created_at",
                "updated_at",
            )
    }
}
