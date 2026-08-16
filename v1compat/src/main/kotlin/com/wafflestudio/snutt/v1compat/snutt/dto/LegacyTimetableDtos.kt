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
    val id: String?,
    val userId: String,
    val year: Int,
    val semester: Semester,
    val lectures: List<LegacyTimetableLectureDto>,
    val title: String,
    val theme: Int,
    val themeId: String?,
    val isPrimary: Boolean,
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
        id = timetable.externalId,
        userId = userId,
        year = timetable.year,
        semester = timetable.semester,
        lectures = display.lectures.map { LegacyTimetableLectureDto(it, it.lectureExternalId?.let(evLectureIds::get), language) },
        title = timetable.title,
        theme = if (display.themeIsBuiltin) display.themeBuiltinType else BasicThemeType.SNUTT.value,
        themeId = if (display.themeIsBuiltin) null else display.themeExternalId,
        isPrimary = timetable.isPrimary,
        updatedAt = checkNotNull(timetable.updatedAt),
    )

data class LegacyTimetableLectureDto(
    val id: String?,
    val academicYear: String?,
    val category: String?,
    val classPlaceAndTimes: List<LegacyClassPlaceAndTimeDto>,
    val classification: String?,
    val credit: Int?,
    val department: String?,
    val instructor: String?,
    val lectureNumber: String?,
    val quota: Int?,
    val freshmanQuota: Int?,
    val remark: String?,
    val courseNumber: String?,
    val courseTitle: String,
    val color: LegacyColorSetDto?,
    val colorIndex: Int,
    val lectureId: String?,
    val snuttEvLecture: LegacyEvLectureIdDto?,
    val categoryPre2025: String?,
)

fun LegacyTimetableLectureDto(
    display: TimetableLectureDisplay,
    evLectureId: Long?,
    language: Language = Language.KO,
): LegacyTimetableLectureDto =
    LegacyTimetableLectureDto(
        id = display.externalId,
        academicYear = language.select(display.academicYear, display.academicYearEn),
        category = language.select(display.category, display.categoryEn),
        classPlaceAndTimes = display.classPlaceAndTimes.map { LegacyClassPlaceAndTimeDto(it) },
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
        lectureId = display.lectureExternalId,
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

data class LegacyClassPlaceAndTimeDto(
    val day: Int,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
)

fun LegacyClassPlaceAndTimeDto(time: ClassPlaceAndTime): LegacyClassPlaceAndTimeDto =
    LegacyClassPlaceAndTimeDto(
        day = time.day.value,
        place = time.place,
        startMinute = time.startMinute,
        endMinute = time.endMinute,
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
