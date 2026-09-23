package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.EvSource
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.requireStr
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class CourseStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
    private val ev: EvSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "course"
    override val tables = listOf("course")

    override fun run() {
        val fromEv = migrateEvLectures()
        val minted = mintMissingCourses(fromEv)
        alignAutoIncrement(
            "course",
            context.courseIds.values
                .maxOrNull()
                ?.plus(1) ?: 1L,
        )
        log.info("course 이관: 구 ev {}건 + 신규 {}건 = {}건", fromEv, minted, context.courseIds.size)
    }

    private fun migrateEvLectures(): Int {
        var count = 0
        writer("course", COLUMNS).use { out ->
            ev.jdbc.query(
                "SELECT id, course_number, instructor, title, created_at, updated_at FROM lecture ORDER BY id",
            ) { rs ->
                val id = rs.getLong("id")
                val courseNumber = rs.getString("course_number").trim()
                val instructor = rs.getString("instructor").trim()
                val key = context.courseKey(courseNumber, instructor)
                val existing = context.courseIds[key]
                if (existing != null) {
                    context.courseIdRemap[id] = existing
                    context.resolved(MigrationSupport.ResolutionReasons.EV_COURSE_DUPLICATE)
                    return@query
                }
                context.courseIds[key] = id
                out.add(
                    id,
                    courseNumber,
                    instructor,
                    rs.getString("title"),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("updated_at"),
                )
                count++
            }
        }
        return count
    }

    private fun mintMissingCourses(fromEv: Int): Int {
        val pending = LinkedHashMap<String, Array<Any?>>()
        mongo.each("lectures") { doc ->
            val courseNumber = doc.requireStr("course_number").trim()
            val instructor = doc.str("instructor").orEmpty().trim()
            val key = context.courseKey(courseNumber, instructor)
            if (context.courseIds.containsKey(key)) return@each
            pending[key] =
                arrayOf(
                    courseNumber,
                    instructor,
                    doc.requireStr("course_title"),
                )
        }
        if (pending.isEmpty()) return 0

        val ids = IdSequence((context.courseIds.values.maxOrNull() ?: 0L) + 1)
        val now = Instant.now().toSqlTimestamp()
        writer("course", COLUMNS).use { out ->
            pending.forEach { (key, values) ->
                val id = ids.next()
                context.courseIds[key] = id
                out.add(id, *values, now, now)
            }
        }
        log.info("구 ev에 없던 course {}건을 강의에서 만들었다 (구 ev {}건)", pending.size, fromEv)
        return pending.size
    }

    companion object {
        private val COLUMNS =
            listOf(
                "id",
                "course_number",
                "instructor",
                "title",
                "created_at",
                "updated_at",
            )
    }
}
