package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
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
import java.sql.Timestamp
import java.time.Instant

@Component
class TimetableStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "timetable"
    override val tables = listOf("timetable_lecture_reminder", "timetable_lecture", "timetable")

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
                    val themeId =
                        doc.oid("themeId")?.let(context.themeIds::get)
                            ?: ((doc.int("theme") ?: 0) + 1L)

                    timetables.add(
                        id,
                        userId,
                        year,
                        semester,
                        uniqueTitle(userId, year, semester, doc.str("title").orEmpty(), takenTitles),
                        themeId,
                        doc.bool("is_primary"),
                        updatedAt,
                        updatedAt,
                    )

                    doc.docs("lecture_list").forEach { item ->
                        val lectureId = lectureIds.next()
                        context.timetableLectureIds[item.id()] = lectureId
                        lectures.add(*item.toRow(lectureId, id, updatedAt))
                        lectureCount++
                    }
                }
            }
        }
        alignAutoIncrement("timetable", timetableIds.peek())
        alignAutoIncrement("timetable_lecture", lectureIds.peek())
        log.info("시간표 이관: {}건 (제외 {}건), 시간표 강의 {}건", timetableIds.peek() - 1, skipped, lectureCount)
        migrateReminders()
    }

    private fun migrateReminders() {
        val ids = IdSequence()
        var count = 0L
        val scheduleIds = IdSequence()
        var scheduleCount = 0L
        writer(
            "timetable_lecture_reminder",
            listOf(
                "id",
                "timetable_lecture_id",
                "offset_minutes",
                "created_at",
                "updated_at",
            ),
        ).use { reminderOut ->
            writer(
                "timetable_lecture_reminder_schedule",
                listOf(
                    "id",
                    "reminder_id",
                    "day",
                    "minute",
                    "recent_notified_at",
                    "created_at",
                    "updated_at",
                ),
            ).use { scheduleOut ->
                mongo.each("timetableLectureReminder") { doc ->
                    val timetableLectureId =
                        doc.oid("timetableLectureId")?.let(context.timetableLectureIds::get) ?: return@each
                    val schedules =
                        doc.docs("schedules").mapNotNull { schedule ->
                            val day =
                                when (val raw = schedule.get("day")) {
                                    is String -> DayOfWeek.valueOf(raw)
                                    is Number -> DayOfWeek.getOfValue(raw.toInt())
                                    else -> null
                                } ?: return@mapNotNull null
                            val minute = schedule.int("minute") ?: return@mapNotNull null
                            // recentNotifiedAt는 11분 내 중복 방지에만 쓰이므로 이관하지 않는다
                            Schedule(day, minute)
                        }
                    if (schedules.isEmpty()) return@each

                    val now = Instant.now().toSqlTimestamp()
                    val reminderId = ids.next()
                    reminderOut.add(
                        reminderId,
                        timetableLectureId,
                        doc.int("offsetMinutes") ?: 0,
                        now,
                        now,
                    )
                    schedules.forEach { schedule ->
                        scheduleOut.add(
                            scheduleIds.next(),
                            reminderId,
                            schedule.day.value,
                            schedule.minute,
                            null,
                            now,
                            now,
                        )
                        scheduleCount++
                    }
                    count++
                }
            }
        }
        alignAutoIncrement("timetable_lecture_reminder", ids.peek())
        alignAutoIncrement("timetable_lecture_reminder_schedule", scheduleIds.peek())
        log.info("리마인더 이관: {}건(스케줄 {}건)", count, scheduleCount)
    }

    private fun Document.toRow(
        id: Long,
        timetableId: Long,
        updatedAt: Timestamp,
    ): Array<Any?> {
        val lectureId = oid("lecture_id")?.let(context.lectureIds::get)
        val snapshot = lectureId?.let(context.lectureSnapshots::get)
        val places = docs("class_time_json")

        fun <T> override(
            value: T?,
            original: (LectureSnapshot) -> T?,
        ): T? = if (snapshot == null) value else value.takeIf { it != original(snapshot) }

        val classTimeChanged = snapshot == null || LectureStep.classTimeKey(places) != snapshot.classTimeKey
        val overrides =
            buildMap<String, Any?> {
                override(str("course_title")) { it.courseTitle }?.let { put("courseTitle", it) }
                override(str("instructor")) { it.instructor }?.let { put("instructor", it) }
                override(int("credit")) { it.credit }?.let { put("credit", it) }
                override(str("remark")) { it.remark }?.let { put("remark", it) }
                if (classTimeChanged) {
                    val times = places.map { it.toClassPlaceAndTime() }
                    if (times.isNotEmpty()) put("classPlaceAndTimes", times)
                }
                override(str("academic_year")) { it.academicYear }?.let { put("academicYear", it) }
                override(str("category")) { it.category }?.let { put("category", it) }
                override(str("classification")) { it.classification }?.let { put("classification", it) }
                override(str("categoryPre2025")) { it.categoryPre2025 }?.let { put("categoryPre2025", it) }
            }
        return arrayOf(
            id,
            timetableId,
            lectureId,
            doc("color")?.let { Json.write(mapOf("backgroundColor" to it.str("bg"), "foregroundColor" to it.str("fg"))) },
            int("colorIndex") ?: 0,
            if (overrides.isEmpty()) null else Json.write(overrides),
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
                "user_id",
                "year",
                "semester",
                "title",
                "theme_id",
                "is_primary",
                "created_at",
                "updated_at",
            )
        private val TIMETABLE_LECTURE_COLUMNS =
            listOf(
                "id",
                "timetable_id",
                "lecture_id",
                "color",
                "color_index",
                "overrides",
                "created_at",
                "updated_at",
            )
    }
}
