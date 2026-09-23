package com.wafflestudio.snutt.migration.step

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.LectureSnapshot
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.nullIfBlank
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.requireInt
import com.wafflestudio.snutt.migration.requireOid
import com.wafflestudio.snutt.migration.requireStr
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant

@Component
class TimetableStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
    private val jsonMapper: JsonMapper,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "timetable"
    override val tables = listOf("timetable_lecture_reminder_schedule", "timetable_lecture_reminder", "timetable_lecture", "timetable")

    override fun run() {
        val timetableIds = IdSequence()
        val lectureIds = IdSequence()
        val takenTitles = HashSet<String>(1_024_000)
        var lectureCount = 0L
        var skipped = 0L
        val primaries = primaryTimetableIds()

        writer("timetable", TIMETABLE_COLUMNS).use { timetables ->
            writer("timetable_lecture", TIMETABLE_LECTURE_COLUMNS, parent = timetables).use { lectures ->
                mongo.each("timetables") { doc ->
                    val userId = context.userIds[doc.oid("user_id")]
                    if (userId == null) {
                        skipped++
                        context.resolved(MigrationSupport.ResolutionReasons.TIMETABLE_USER_MISSING)
                        repeat(
                            doc.docs("lecture_list").size,
                        ) { context.resolved(MigrationSupport.ResolutionReasons.TIMETABLE_LECTURE_USER_MISSING) }
                        return@each
                    }
                    val id = timetableIds.next()
                    val externalId = doc.id()
                    context.timetableIds[externalId] = id
                    val year = doc.requireInt("year")
                    val semester = doc.requireInt("semester")
                    val updatedAt = doc.instant("updated_at").orNow().toSqlTimestamp()
                    val themeId = resolveThemeId(doc)
                    val isPrimary = doc.bool("is_primary") && externalId in primaries
                    if (doc.bool("is_primary") && !isPrimary) {
                        context.resolved(MigrationSupport.ResolutionReasons.PRIMARY_TIMETABLE_DUPLICATE)
                    }

                    timetables.add(
                        id,
                        userId,
                        year,
                        semester,
                        uniqueTitle(userId, year, semester, doc.requireStr("title"), takenTitles),
                        themeId,
                        isPrimary,
                        updatedAt,
                        updatedAt,
                    )

                    doc.docs("lecture_list").forEach { item ->
                        val lectureId = lectureIds.next()
                        context.timetableLectureIds[externalId to item.id()] = lectureId
                        item.oid("lecture_id")?.let(context.lectureIds::get)?.let {
                            context.timetableLectureIdsByLecture.putIfAbsent(id to it, lectureId)
                        }
                        lectures.add(*item.toRow(lectureId, id, updatedAt, context.themePalettes.getValue(themeId)))
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

    private fun primaryTimetableIds(): Set<String> {
        val latest = HashMap<String, Document>()
        mongo
            .collection("timetables")
            .find(Filters.eq("is_primary", true))
            .projection(Projections.include("user_id", "year", "semester", "updated_at"))
            .forEach { doc ->
                val key = "${doc.oid("user_id")}\u0000${doc.requireInt("year")}\u0000${doc.requireInt("semester")}"
                latest.merge(key, doc) { previous, current -> maxOf(previous, current, PRIMARY_ORDER) }
            }
        return latest.values.mapTo(HashSet()) { it.id() }
    }

    private fun resolveThemeId(doc: Document): Long {
        val externalThemeId = doc.oid("themeId") ?: return doc.requireInt("theme") + 1L
        return context.themeIds[externalThemeId] ?: run {
            context.resolved(MigrationSupport.ResolutionReasons.THEME_MISSING)
            DEFAULT_THEME_ID
        }
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
                parent = reminderOut,
            ).use { scheduleOut ->
                mongo.each("timetableLectureReminder") { doc ->
                    val timetableLectureId =
                        context.timetableLectureIds[
                            doc.requireOid("timetableId") to
                                doc.requireOid("timetableLectureId"),
                        ]
                    if (timetableLectureId == null) {
                        context.resolved(MigrationSupport.ResolutionReasons.REMINDER_TIMETABLE_LECTURE_MISSING)
                        return@each
                    }
                    val schedules =
                        doc.docs("schedules").map { schedule ->
                            Schedule(DayOfWeek.fromValue(schedule.requireInt("day")), schedule.requireInt("minute"))
                        }

                    val now = Instant.now().toSqlTimestamp()
                    val reminderId = ids.next()
                    reminderOut.add(
                        reminderId,
                        timetableLectureId,
                        doc.requireInt("offsetMinutes"),
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
        palette: List<ColorSet>,
    ): Array<Any?> {
        val lectureId = oid("lecture_id")?.let(context.lectureIds::get)
        val snapshot = lectureId?.let(context.lectureSnapshots::get)
        val places = docs("class_time_json")

        fun <T> override(
            value: T?,
            original: (LectureSnapshot) -> T?,
        ): T? = if (snapshot == null) value else value.takeIf { it != original(snapshot) }

        fun textOverride(
            value: String?,
            original: (LectureSnapshot) -> String?,
        ): String? =
            if (snapshot == null) {
                value.nullIfBlank()
            } else {
                value.takeIf { it.nullIfBlank() != original(snapshot) }
            }

        val classTimeChanged = snapshot == null || LectureStep.classTimeKey(places) != snapshot.classTimeKey
        val overrides =
            buildMap<String, Any?> {
                override(str("course_title")) { it.courseTitle }?.let { put("courseTitle", it) }
                textOverride(str("instructor")) { it.instructor }?.let { put("instructor", it) }
                override(int("credit")) { it.credit }?.let { put("credit", it) }
                textOverride(str("remark")) { it.remark }?.let { put("remark", it) }
                if (classTimeChanged) {
                    put("classPlaceAndTimes", places.map { it.toClassPlaceAndTime() })
                }
                textOverride(str("academic_year")) { it.academicYear }?.let { put("academicYear", it) }
                textOverride(str("category")) { it.category }?.let { put("category", it) }
                textOverride(str("classification")) { it.classification }?.let { put("classification", it) }
                textOverride(str("categoryPre2025")) { it.categoryPre2025 }?.let { put("categoryPre2025", it) }
            }
        val colorIndex = requireInt("colorIndex")
        val color = if (colorIndex == 0) customColor() else null
        val matchedIndex =
            color?.let { old ->
                palette.indices
                    .filter {
                        palette[it].backgroundColor.equals(old.backgroundColor, true) &&
                            palette[it].foregroundColor.equals(old.foregroundColor, true)
                    }.singleOrNull()
            }
        val oldIndex = (colorIndex - 1).coerceAtLeast(0)
        if (matchedIndex == null && oldIndex >= palette.size) {
            context.resolved(MigrationSupport.ResolutionReasons.PALETTE_INDEX_OUT_OF_RANGE)
        }
        return arrayOf(
            id,
            timetableId,
            lectureId,
            color?.takeIf { matchedIndex == null }?.let(jsonMapper::writeValueAsString),
            matchedIndex ?: (oldIndex % palette.size),
            if (overrides.isEmpty()) null else jsonMapper.writeValueAsString(overrides),
            updatedAt,
            updatedAt,
        )
    }

    private fun Document.customColor(): ColorSet? {
        val color = doc("color")?.takeIf { it.isNotEmpty() } ?: return null
        val backgroundColor = color.str("bg")
        val foregroundColor = color.str("fg")
        if (backgroundColor == null || foregroundColor == null) {
            context.resolved(MigrationSupport.ResolutionReasons.INVALID_CUSTOM_COLOR)
            return null
        }
        return try {
            ColorSet(backgroundColor, foregroundColor)
        } catch (e: SnuttException) {
            context.resolved(MigrationSupport.ResolutionReasons.INVALID_CUSTOM_COLOR)
            null
        }
    }

    private fun Document.toClassPlaceAndTime(): Map<String, Any?> =
        mapOf(
            "day" to requireInt("day"),
            "place" to requireStr("place"),
            "startMinute" to requireInt("startMinute"),
            "endMinute" to requireInt("endMinute"),
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
        context.resolved(MigrationSupport.ResolutionReasons.TIMETABLE_TITLE_DUPLICATE)
        return "$title ($suffix)"
    }

    companion object {
        private const val DEFAULT_THEME_ID = 1L
        private val PRIMARY_ORDER = compareBy<Document>({ it.instant("updated_at") ?: Instant.EPOCH }, { it.id() })

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
                "custom_color",
                "palette_index",
                "overrides",
                "created_at",
                "updated_at",
            )
    }
}
