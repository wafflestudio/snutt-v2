package com.wafflestudio.snutt.v1compat.snutt.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor

data class LegacyLoginResponse(
    @param:JsonProperty("user_id")
    val userId: String,
    val token: String,
    val message: String = "ok",
)

data class LegacyTimetableDto(
    @param:JsonProperty("_id")
    val id: String?,
    @param:JsonProperty("user_id")
    val userId: String,
    val year: Int,
    val semester: Semester,
    @param:JsonProperty("lecture_list")
    val lectures: List<LegacyTimetableLectureDto>,
    val title: String,
    val theme: Int,
    val themeId: String?,
    val isPrimary: Boolean,
    @param:JsonProperty("updated_at")
    val updatedAt: Instant,
)

fun LegacyTimetableDto(
    timetable: Timetable,
    userId: String,
    display: TimetableDisplay,
    evLectureIds: Map<String, Long>,
    language: Language = Language.KO,
): LegacyTimetableDto =
    LegacyTimetableDto(
        id = timetable.id!!.toString(),
        userId = userId,
        year = timetable.year,
        semester = timetable.semester,
        lectures = display.lectures.map { LegacyTimetableLectureDto(it, it.lectureId?.toString()?.let(evLectureIds::get), language) },
        title = timetable.title,
        theme = if (timetable.themeId in 1..6) (timetable.themeId - 1).toInt() else BasicThemeType.SNUTT.value,
        themeId = if (timetable.themeId in 1..6) null else timetable.themeId.toString(),
        isPrimary = timetable.isPrimary,
        updatedAt = checkNotNull(timetable.updatedAt),
    )

data class LegacyTimetableLectureDto(
    @param:JsonProperty("_id")
    val id: String?,
    @param:JsonProperty("academic_year")
    val academicYear: String?,
    val category: String?,
    @param:JsonProperty("class_time_json")
    val classPlaceAndTimes: List<LegacyClassPlaceAndTimeFullDto>,
    val classification: String?,
    val credit: Int?,
    val department: String?,
    val instructor: String?,
    @param:JsonProperty("lecture_number")
    val lectureNumber: String?,
    val quota: Int?,
    @param:JsonProperty("freshman_quota")
    val freshmanQuota: Int?,
    val remark: String?,
    @param:JsonProperty("course_number")
    val courseNumber: String?,
    @param:JsonProperty("course_title")
    val courseTitle: String,
    val color: LegacyColorSetDto?,
    @param:JsonProperty("colorIndex")
    val colorIndex: Int,
    @param:JsonProperty("lecture_id")
    val lectureId: String?,
    val snuttEvLecture: LegacyEvLectureIdDto?,
    @param:JsonProperty("categoryPre2025")
    val categoryPre2025: String?,
)

fun LegacyTimetableLectureDto(
    display: TimetableLectureDisplay,
    evLectureId: Long?,
    language: Language = Language.KO,
): LegacyTimetableLectureDto =
    LegacyTimetableLectureDto(
        id = display.id.toString(),
        academicYear = language.select(display.academicYear, display.academicYearEn),
        category = language.select(display.category, display.categoryEn),
        classPlaceAndTimes = display.classPlaceAndTimes.map { LegacyClassPlaceAndTimeFullDto(it) },
        classification = language.select(display.classification, display.classificationEn),
        credit = display.credit,
        department = language.select(display.department, display.departmentEn),
        instructor = language.select(display.instructor, display.instructorEn),
        lectureNumber = display.lectureNumber,
        quota = display.quota,
        freshmanQuota = display.freshmanQuota,
        remark = language.select(display.remark, display.remarkEn),
        courseNumber = display.courseNumber,
        courseTitle = language.select(display.courseTitle, display.courseTitleEn),
        color = display.color?.let { LegacyColorSetDto(bg = it.backgroundColor, fg = it.foregroundColor) },
        colorIndex = display.colorIndex,
        lectureId = display.lectureId?.toString(),
        snuttEvLecture = evLectureId?.let { LegacyEvLectureIdDto(it) },
        categoryPre2025 = display.categoryPre2025,
    )

data class LegacyEvLectureIdDto(
    val evLectureId: Long,
)

data class LegacyEvSummary(
    val evLectureId: Long,
    val avgRating: Double?,
    val evaluationCount: Long,
)

data class LegacyColorSetDto(
    val bg: String?,
    val fg: String?,
)

data class LegacyClassPlaceAndTimeFullDto(
    val day: Int,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
    @param:JsonProperty("start_time")
    val startTime: String,
    @param:JsonProperty("end_time")
    val endTime: String,
    @param:JsonProperty("len")
    val periodLength: Double,
    @param:JsonProperty("start")
    val startPeriod: Double,
)

fun LegacyClassPlaceAndTimeFullDto(time: ClassPlaceAndTime): LegacyClassPlaceAndTimeFullDto =
    LegacyClassPlaceAndTimeFullDto(
        day = time.day.value,
        place = time.place,
        startMinute = time.startMinute,
        endMinute = time.endMinute,
        startTime = minuteToString(time.startMinute),
        endTime = minuteToString(time.endMinute),
        startPeriod = time.startPeriod,
        periodLength = time.endPeriod - time.startPeriod,
    )

private fun minuteToString(minute: Int) = "${String.format("%02d", minute / 60)}:${String.format("%02d", minute % 60)}"

private val ClassPlaceAndTime.startPeriod: Double
    get() = floor((startMinute - 8 * 60).toDouble() / 60 * 2) / 2

private val ClassPlaceAndTime.endPeriod: Double
    get() = ceil((endMinute - 8 * 60).toDouble() / 60 * 2) / 2
