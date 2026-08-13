package com.wafflestudio.snutt.api.v1compat.snutt.dto

import com.fasterxml.jackson.annotation.JsonProperty
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

// v1 LoginResponse (../snutt/users/dto/LoginResponse.kt)
data class LegacyLoginResponse(
    @param:JsonProperty("user_id")
    val userId: String,
    val token: String,
    val message: String = "ok",
)

// v1 TimetableDto — 전체 응답은 camelCase. 시각은 Instant(ISO 문자열)
data class LegacyTimetableDto(
    val id: String?,
    val userId: String,
    val year: Int,
    val semester: Semester,
    val lectures: List<LegacyTimetableLectureDto>,
    val title: String,
    val theme: BasicThemeType,
    val themeId: String?,
    val isPrimary: Boolean,
    val updatedAt: Instant,
)

fun LegacyTimetableDto(
    timetable: Timetable,
    userId: String,
    display: TimetableDisplay,
    // lecture 공개 id(hex) → ev 요약 (snuttEvLecture.evLectureId)
    evLectureIds: Map<String, Long>,
    language: com.wafflestudio.snutt.core.common.client.Language = com.wafflestudio.snutt.core.common.client.Language.KO,
): LegacyTimetableDto =
    LegacyTimetableDto(
        id = timetable.externalId,
        userId = userId,
        year = timetable.year,
        semester = timetable.semester,
        lectures = display.lectures.map { LegacyTimetableLectureDto(it, it.lectureId?.let(evLectureIds::get), language) },
        title = timetable.title,
        theme = timetable.theme,
        themeId = display.themeExternalId,
        isPrimary = timetable.isPrimary,
        updatedAt = checkNotNull(timetable.updatedAt),
    )

// v1 TimetableLectureDto — camelCase. classPlaceAndTimes는 단순 DTO, snuttEvLecture는 evLectureId만
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
    language: com.wafflestudio.snutt.core.common.client.Language = com.wafflestudio.snutt.core.common.client.Language.KO,
): LegacyTimetableLectureDto =
    LegacyTimetableLectureDto(
        id = display.id,
        academicYear = language.select(display.academicYear, display.academicYearEn),
        category = language.select(display.category, display.categoryEn),
        classPlaceAndTimes = display.classPlaceAndTime.map { LegacyClassPlaceAndTimeDto(it) },
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
        lectureId = display.lectureId,
        snuttEvLecture = evLectureId?.let { LegacyEvLectureIdDto(it) },
        categoryPre2025 = display.categoryPre2025,
    )

// v1 SnuttEvLectureIdDto (시간표 응답용)
data class LegacyEvLectureIdDto(
    val evLectureId: Long,
)

// v1 SnuttEvLectureSummaryDto (검색/북마크/빈자리 응답용)
data class LegacyEvSummary(
    val evLectureId: Long,
    val avgRating: Double?,
    val evaluationCount: Long,
)

data class LegacyColorSetDto(
    val bg: String?,
    val fg: String?,
)

// v1 ClassPlaceAndTimeDto — camelCase 단순 DTO (시간표 응답)
data class LegacyClassPlaceAndTimeDto(
    val day: Int,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
)

fun LegacyClassPlaceAndTimeDto(classPlaceAndTime: ClassPlaceAndTime): LegacyClassPlaceAndTimeDto =
    LegacyClassPlaceAndTimeDto(
        day = classPlaceAndTime.day.value,
        place = classPlaceAndTime.place,
        startMinute = classPlaceAndTime.startMinute,
        endMinute = classPlaceAndTime.endMinute,
    )

// v1 ClassPlaceAndTimeLegacyDto — start_time/end_time/len/start 포함 (강의 검색/북마크/빈자리)
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

fun LegacyClassPlaceAndTimeFullDto(classPlaceAndTime: ClassPlaceAndTime): LegacyClassPlaceAndTimeFullDto =
    LegacyClassPlaceAndTimeFullDto(
        day = classPlaceAndTime.day.value,
        place = classPlaceAndTime.place,
        startMinute = classPlaceAndTime.startMinute,
        endMinute = classPlaceAndTime.endMinute,
        startTime = minuteToString(classPlaceAndTime.startMinute),
        endTime = minuteToString(classPlaceAndTime.endMinute),
        startPeriod = classPlaceAndTime.startPeriod,
        periodLength = classPlaceAndTime.endPeriod - classPlaceAndTime.startPeriod,
    )

private fun minuteToString(minute: Int) = "${String.format("%02d", minute / 60)}:${String.format("%02d", minute % 60)}"

private val ClassPlaceAndTime.startPeriod: Double
    get() = floor((startMinute - 8 * 60).toDouble() / 60 * 2) / 2

private val ClassPlaceAndTime.endPeriod: Double
    get() = ceil((endMinute - 8 * 60).toDouble() / 60 * 2) / 2
