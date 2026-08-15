package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.Json
import com.wafflestudio.snutt.migration.LectureSnapshot
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class TimetableStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "timetable"
    override val tables = listOf("timetable_lecture", "timetable")

    override fun run() {
        val timetableIds = IdSequence()
        val lectureIds = IdSequence()
        val takenTitles = HashSet<String>(1_024_000)
        var lectureCount = 0L
        var skipped = 0L

        writer("timetable", TIMETABLE_COLUMNS).use { timetables ->
            writer("timetable_lecture", TIMETABLE_LECTURE_COLUMNS, parent = timetables).use { lectures ->
                mongo.each("timetables") { doc ->
                    val userId = context.userIds[doc.oid("user_id")]
                    if (userId == null) {
                        skipped++
                        context.resolved("사용자가 없는 시간표를 제외")
                        return@each
                    }
                    val id = timetableIds.next()
                    val year = doc.int("year") ?: 0
                    val semester = doc.int("semester") ?: 1
                    val updatedAt = doc.instant("updated_at").orNow().toSqlTimestamp()
                    val themeId = doc.oid("themeId")?.let(context.themeIds::get)

                    timetables.add(
                        id,
                        doc.id(),
                        userId,
                        year,
                        semester,
                        uniqueTitle(userId, year, semester, doc.str("title").orEmpty(), takenTitles),
                        doc.int("theme") ?: 0,
                        themeId,
                        doc.bool("is_primary"),
                        updatedAt,
                        updatedAt,
                    )

                    doc.docs("lecture_list").forEach { item ->
                        lectures.add(*item.toRow(lectureIds.next(), id, updatedAt))
                        lectureCount++
                    }
                }
            }
        }
        alignAutoIncrement("timetable", timetableIds.peek())
        alignAutoIncrement("timetable_lecture", lectureIds.peek())
        log.info("시간표 이관: {}건 (제외 {}건), 시간표 강의 {}건", timetableIds.peek() - 1, skipped, lectureCount)
    }

    private fun Document.toRow(
        id: Long,
        timetableId: Long,
        updatedAt: java.sql.Timestamp,
    ): Array<Any?> {
        val lectureId = oid("lecture_id")?.let(context.lectureIds::get)
        val snapshot = lectureId?.let(context.lectureSnapshots::get)
        val places = docs("class_time_json")

        fun <T> override(
            value: T?,
            original: (LectureSnapshot) -> T?,
        ): T? = if (snapshot == null) value else value.takeIf { it != original(snapshot) }

        val classTimeChanged = snapshot == null || LectureStep.classTimeKey(places) != snapshot.classTimeKey
        return arrayOf(
            id,
            id(),
            timetableId,
            lectureId,
            doc("color")?.let { Json.write(mapOf("backgroundColor" to it.str("bg"), "foregroundColor" to it.str("fg"))) },
            int("colorIndex") ?: 0,
            override(str("course_title")) { it.courseTitle },
            override(str("instructor")) { it.instructor },
            override(int("credit")) { it.credit },
            override(str("remark")) { it.remark },
            if (classTimeChanged) Json.write(places.map { it.toClassPlaceAndTime() }) else null,
            override(str("academic_year")) { it.academicYear },
            override(str("category")) { it.category },
            override(str("classification")) { it.classification },
            override(str("categoryPre2025")) { it.categoryPre2025 },
            updatedAt,
            updatedAt,
        )
    }

    private fun Document.toClassPlaceAndTime(): Map<String, Any?> =
        mapOf(
            "day" to (int("day") ?: 0),
            "place" to str("place").orEmpty(),
            "startMinute" to (int("startMinute") ?: 0),
            "endMinute" to (int("endMinute") ?: 0),
        )

    private fun uniqueTitle(
        userId: Long,
        year: Int,
        semester: Int,
        title: String,
        taken: HashSet<String>,
    ): String {
        fun key(candidate: String) = "$userId\u0000$year\u0000$semester\u0000$candidate"
        if (taken.add(key(title))) return title
        var suffix = 2
        while (!taken.add(key("$title ($suffix)"))) suffix++
        context.resolved("같은 학기에 제목이 중복되어 번호를 붙임")
        return "$title ($suffix)"
    }

    companion object {
        private val TIMETABLE_COLUMNS =
            listOf(
                "id",
                "external_id",
                "user_id",
                "year",
                "semester",
                "title",
                "theme",
                "theme_id",
                "is_primary",
                "created_at",
                "updated_at",
            )
        private val TIMETABLE_LECTURE_COLUMNS =
            listOf(
                "id",
                "external_id",
                "timetable_id",
                "lecture_id",
                "color",
                "color_index",
                "course_title",
                "instructor",
                "credit",
                "remark",
                "class_place_and_time",
                "academic_year",
                "category",
                "classification",
                "category_pre2025",
                "created_at",
                "updated_at",
            )
    }
}
