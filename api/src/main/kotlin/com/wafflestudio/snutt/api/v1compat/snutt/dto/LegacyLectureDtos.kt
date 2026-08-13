package com.wafflestudio.snutt.api.v1compat.snutt.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture

// v1 LectureDto (강의 검색/빈자리 알림 응답) — snake_case + class_time_json + 수강/포화 정보
data class LegacyLectureDto(
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
    val freshmanQuota: Int?,
    val remark: String?,
    val semester: Int,
    val year: Int,
    @param:JsonProperty("course_number")
    val courseNumber: String?,
    @param:JsonProperty("course_title")
    val courseTitle: String,
    val registrationCount: Int,
    val wasFull: Boolean,
    val snuttEvLecture: LegacyEvSummary?,
    val categoryPre2025: String?,
)

fun LegacyLectureDto(
    lecture: Lecture,
    evSummary: LegacyEvSummary? = null,
): LegacyLectureDto =
    LegacyLectureDto(
        id = lecture.externalId,
        academicYear = lecture.academicYear,
        category = lecture.category,
        classPlaceAndTimes = lecture.classPlaceAndTime.map { LegacyClassPlaceAndTimeFullDto(it) },
        classification = lecture.classification,
        credit = lecture.credit,
        department = lecture.department,
        instructor = lecture.instructor,
        lectureNumber = lecture.lectureNumber,
        quota = lecture.quota,
        freshmanQuota = lecture.freshmanQuota,
        remark = lecture.remark,
        semester = lecture.semester.value,
        year = lecture.year,
        courseNumber = lecture.courseNumber,
        courseTitle = lecture.courseTitle,
        registrationCount = lecture.registrationCount,
        wasFull = lecture.wasFull,
        snuttEvLecture = evSummary,
        categoryPre2025 = lecture.categoryPre2025,
    )

// v1 BookmarkLectureDto — semester/year/registrationCount/wasFull 없음
data class LegacyBookmarkLectureDto(
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
    val freshmanQuota: Int?,
    val remark: String?,
    @param:JsonProperty("course_number")
    val courseNumber: String?,
    @param:JsonProperty("course_title")
    val courseTitle: String,
    val snuttEvLecture: LegacyEvSummary?,
    val categoryPre2025: String?,
)

fun com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSummary.toLegacyEvSummary(courseId: Long?): LegacyEvSummary? =
    courseId?.let { LegacyEvSummary(it, avgRating, evalCount) }

fun LegacyBookmarkLectureDto(
    lecture: Lecture,
    evSummary: LegacyEvSummary? = null,
): LegacyBookmarkLectureDto =
    LegacyBookmarkLectureDto(
        id = lecture.externalId,
        academicYear = lecture.academicYear,
        category = lecture.category,
        classPlaceAndTimes = lecture.classPlaceAndTime.map { LegacyClassPlaceAndTimeFullDto(it) },
        classification = lecture.classification,
        credit = lecture.credit,
        department = lecture.department,
        instructor = lecture.instructor,
        lectureNumber = lecture.lectureNumber,
        quota = lecture.quota,
        freshmanQuota = lecture.freshmanQuota,
        remark = lecture.remark,
        courseNumber = lecture.courseNumber,
        courseTitle = lecture.courseTitle,
        snuttEvLecture = evSummary,
        categoryPre2025 = lecture.categoryPre2025,
    )
