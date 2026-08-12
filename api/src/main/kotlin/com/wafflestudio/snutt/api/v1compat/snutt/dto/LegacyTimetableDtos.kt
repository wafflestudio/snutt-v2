package com.wafflestudio.snutt.api.v1compat.snutt.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import kotlin.math.ceil
import kotlin.math.floor

// v1 LoginResponse (../snutt/users/dto/LoginResponse.kt)
data class LegacyLoginResponse(
    @param:JsonProperty("user_id")
    val userId: String,
    val token: String,
    val message: String = "ok",
)

// v1 TimetableDto (snake_case, _id). themeId는 테마의 공개 id(hex)다
data class LegacyTimetableDto(
    @param:JsonProperty("_id")
    val id: String,
    @param:JsonProperty("user_id")
    val userId: String,
    val year: Int,
    val semester: Semester,
    @param:JsonProperty("lecture_list")
    val lectures: List<LegacyTimetableLectureDto>,
    val title: String,
    val theme: BasicThemeType,
    val themeId: String?,
    val isPrimary: Boolean,
    @param:JsonProperty("updated_at")
    val updatedAt: Long,
)

fun LegacyTimetableDto(
    timetable: Timetable,
    userId: String,
    display: TimetableDisplay,
    // lecture 공개 id(hex) → ev 요약
    evSummaries: Map<String, LegacyEvSummary>,
): LegacyTimetableDto =
    LegacyTimetableDto(
        id = timetable.externalId,
        userId = userId,
        year = timetable.year,
        semester = timetable.semester,
        lectures = display.lectures.map { LegacyTimetableLectureDto(it, it.lectureId?.let(evSummaries::get)) },
        title = timetable.title,
        theme = timetable.theme,
        themeId = display.themeExternalId,
        isPrimary = timetable.isPrimary,
        updatedAt = checkNotNull(timetable.updatedAt).toEpochMilli(),
    )

// v1 TimetableLectureLegacyDto. snuttEvLecture의 evLectureId는 재채번된 course id (opaque)
data class LegacyTimetableLectureDto(
    @param:JsonProperty("_id")
    val id: String,
    @param:JsonProperty("academic_year")
    val academicYear: String?,
    val category: String?,
    @param:JsonProperty("class_time_json")
    val classPlaceAndTimes: List<LegacyClassPlaceAndTimeDto>,
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
    val colorIndex: Int,
    @param:JsonProperty("lecture_id")
    val lectureId: String?,
    val snuttEvLecture: LegacyEvSummary?,
    val categoryPre2025: String?,
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

data class LegacyClassPlaceAndTimeDto(
    val day: Int,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
    @param:JsonProperty("start_time")
    val startTime: String,
    @param:JsonProperty("end_time")
    val endTime: String,
    val len: Double,
    val start: Double,
)

fun LegacyTimetableLectureDto(
    display: TimetableLectureDisplay,
    evSummary: LegacyEvSummary?,
): LegacyTimetableLectureDto =
    LegacyTimetableLectureDto(
        id = display.id,
        academicYear = display.academicYear,
        category = display.category,
        classPlaceAndTimes = display.classPlaceAndTime.map { LegacyClassPlaceAndTimeDto(it) },
        classification = display.classification,
        credit = display.credit,
        department = display.department,
        instructor = display.instructor,
        lectureNumber = display.lectureNumber,
        quota = display.quota,
        freshmanQuota = display.freshmanQuota,
        remark = display.remark,
        courseNumber = display.courseNumber,
        courseTitle = display.courseTitle,
        color = display.color?.let { LegacyColorSetDto(bg = it.backgroundColor, fg = it.foregroundColor) },
        colorIndex = display.colorIndex,
        lectureId = display.lectureId,
        snuttEvLecture = evSummary,
        categoryPre2025 = display.categoryPre2025,
    )

fun LegacyClassPlaceAndTimeDto(classPlaceAndTime: ClassPlaceAndTime): LegacyClassPlaceAndTimeDto =
    LegacyClassPlaceAndTimeDto(
        day = classPlaceAndTime.day.value,
        place = classPlaceAndTime.place,
        startMinute = classPlaceAndTime.startMinute,
        endMinute = classPlaceAndTime.endMinute,
        startTime = minuteToString(classPlaceAndTime.startMinute),
        endTime = minuteToString(classPlaceAndTime.endMinute),
        start = classPlaceAndTime.startPeriod,
        len = classPlaceAndTime.endPeriod - classPlaceAndTime.startPeriod,
    )

private fun minuteToString(minute: Int) = "${String.format("%02d", minute / 60)}:${String.format("%02d", minute % 60)}"

private val ClassPlaceAndTime.startPeriod: Double
    get() = floor((startMinute - 8 * 60).toDouble() / 60 * 2) / 2

private val ClassPlaceAndTime.endPeriod: Double
    get() = ceil((endMinute - 8 * 60).toDouble() / 60 * 2) / 2
