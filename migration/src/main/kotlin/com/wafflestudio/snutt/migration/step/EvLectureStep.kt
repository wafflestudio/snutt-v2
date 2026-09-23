package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.EvSource
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.nullIfBlank
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class EvLectureStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val ev: EvSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "evlecture"
    override val tables = emptyList<String>()

    override fun run() {
        val ids = IdSequence(jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM lecture", Long::class.java)!!)
        val offerings = HashSet<Triple<Long, Int, Int>>()
        val lectureNumbers = HashMap<Triple<Int, Int, String>, Int>()
        var count = 0L
        writer("lecture", COLUMNS).use { out ->
            ev.jdbc.query(
                "SELECT sl.id, sl.year, sl.semester, sl.academic_year, sl.category, sl.classification, sl.credit, sl.extra_info, " +
                    "sl.created_at, sl.updated_at, l.id AS lecture_id, l.course_number, l.instructor, l.title, l.department " +
                    "FROM semester_lecture sl JOIN lecture l ON l.id = sl.lecture_id ORDER BY sl.id",
            ) { rs ->
                val year = rs.getInt("year")
                val semester = rs.getInt("semester")
                if (year to semester in context.lectureSemesters) return@query
                val evLectureId = rs.getLong("lecture_id")
                val courseId = context.courseIdRemap[evLectureId] ?: evLectureId
                if (!offerings.add(Triple(courseId, year, semester))) {
                    context.resolved(MigrationSupport.ResolutionReasons.EV_LECTURE_DUPLICATE)
                    return@query
                }
                val courseNumber = rs.getString("course_number").trim()
                val lectureNumber = lectureNumbers.merge(Triple(year, semester, courseNumber), 1, Int::plus)!!
                out.add(
                    ids.next(),
                    courseId,
                    year,
                    semester,
                    courseNumber,
                    lectureNumber.toString().padStart(3, '0'),
                    rs.getString("title"),
                    rs.getString("instructor").trim().nullIfBlank(),
                    rs.getString("department").nullIfBlank(),
                    rs.getString("academic_year").nullIfBlank(),
                    rs.getString("category").nullIfBlank(),
                    rs.getString("classification").nullIfBlank(),
                    rs.getInt("credit"),
                    rs.getString("extra_info").nullIfBlank(),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("updated_at"),
                )
                count++
            }
        }
        alignAutoIncrement("lecture", ids.peek())
        log.info("구 SNUTT 강의가 없는 학기의 구 ev 강의 이관: {}건", count)
    }

    companion object {
        private val COLUMNS =
            listOf(
                "id",
                "course_id",
                "year",
                "semester",
                "course_number",
                "lecture_number",
                "course_title",
                "instructor",
                "department",
                "academic_year",
                "category",
                "classification",
                "credit",
                "remark",
                "created_at",
                "updated_at",
            )
    }
}
