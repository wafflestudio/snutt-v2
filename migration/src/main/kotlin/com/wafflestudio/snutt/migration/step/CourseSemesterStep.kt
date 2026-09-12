package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.EvSource
import com.wafflestudio.snutt.migration.MigrationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class CourseSemesterStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val ev: EvSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "coursesemester"
    override val tables = listOf("course_semester")

    override fun run() {
        if (ev.available) {
            writer("course_semester", COLUMNS).use { out ->
                ev.jdbc.query(
                    "SELECT s.id, s.lecture_id, s.year, s.semester, s.credit, s.academic_year, s.category, s.classification, " +
                        "s.extra_info, s.created_at, s.updated_at, c.department FROM semester_lecture s " +
                        "JOIN lecture c ON c.id = s.lecture_id ORDER BY s.id",
                ) { rs ->
                    out.add(
                        rs.getLong("id"),
                        rs.getLong("lecture_id"),
                        rs.getInt("year"),
                        rs.getInt("semester"),
                        rs.getInt("credit"),
                        rs.getString("academic_year"),
                        rs.getString("category"),
                        rs.getString("classification"),
                        rs.getString("extra_info"),
                        rs.getString("department"),
                        rs.getTimestamp("created_at"),
                        rs.getTimestamp("updated_at"),
                    )
                }
            }
        }
        jdbc.update(
            """
            INSERT INTO course_semester
                (course_id, year, semester, credit, academic_year, category, classification, extra_info, department, created_at, updated_at)
            SELECT l.course_id, l.year, l.semester, l.credit, l.academic_year, l.category, l.classification,
                   l.remark, l.department, l.created_at, l.updated_at
            FROM lecture l
            JOIN (
                SELECT MIN(id) AS id FROM lecture WHERE course_id IS NOT NULL GROUP BY course_id, year, semester
            ) first_offering ON first_offering.id = l.id
            WHERE NOT EXISTS (
                SELECT 1 FROM course_semester cs
                WHERE cs.course_id = l.course_id AND cs.year = l.year AND cs.semester = l.semester
            )
            """.trimIndent(),
        )
        jdbc.update(
            """
            UPDATE course_semester cs
            JOIN (
                SELECT course_id, year, semester, MIN(id) AS id FROM lecture
                WHERE course_id IS NOT NULL GROUP BY course_id, year, semester
            ) first_offering ON first_offering.course_id = cs.course_id
                AND first_offering.year = cs.year AND first_offering.semester = cs.semester
            JOIN lecture l ON l.id = first_offering.id
            SET cs.department = l.department, cs.department_en = l.department_en,
                cs.academic_year_en = l.academic_year_en, cs.category_en = l.category_en,
                cs.classification_en = l.classification_en, cs.category_pre2025 = l.category_pre2025
            """.trimIndent(),
        )
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM course_semester", Long::class.java)
        log.info("과목 개설 학기 이관: {}건", count)
    }

    companion object {
        private val COLUMNS =
            listOf(
                "id",
                "course_id",
                "year",
                "semester",
                "credit",
                "academic_year",
                "category",
                "classification",
                "extra_info",
                "department",
                "created_at",
                "updated_at",
            )
    }
}
