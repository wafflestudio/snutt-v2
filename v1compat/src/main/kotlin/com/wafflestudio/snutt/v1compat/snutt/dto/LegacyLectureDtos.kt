package com.wafflestudio.snutt.v1compat.snutt.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSummary
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus

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
    classTimes: List<ClassPlaceAndTime>,
    language: Language,
    evaluationSummary: LegacyEvSummary? = null,
    status: LectureRegistrationStatus? = null,
): LegacyLectureDto =
    LegacyLectureDto(
        id = lecture.id!!.toString(),
        academicYear = language.select(lecture.academicYear, lecture.academicYearEn),
        category = language.select(lecture.category, lecture.categoryEn),
        classPlaceAndTimes = classTimes.map { LegacyClassPlaceAndTimeFullDto(it) },
        classification = language.select(lecture.classification, lecture.classificationEn),
        credit = lecture.credit,
        department = language.select(lecture.department, lecture.departmentEn),
        instructor = language.select(lecture.instructor, lecture.instructorEn),
        lectureNumber = lecture.lectureNumber,
        quota = lecture.quota,
        freshmanQuota = lecture.freshmanQuota,
        remark = language.select(lecture.remark, lecture.remarkEn),
        semester = lecture.semester.value,
        year = lecture.year,
        courseNumber = lecture.courseNumber,
        courseTitle = language.select(lecture.courseTitle, lecture.courseTitleEn),
        registrationCount = status?.registrationCount ?: 0,
        wasFull = status?.wasFull ?: false,
        snuttEvLecture = evaluationSummary,
        categoryPre2025 = lecture.categoryPre2025,
    )

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

fun EvaluationSummary.toLegacyEvSummary(courseId: Long?): LegacyEvSummary? = courseId?.let { LegacyEvSummary(it, avgRating, evalCount) }

fun LegacyBookmarkLectureDto(
    lecture: Lecture,
    classTimes: List<ClassPlaceAndTime>,
    language: Language,
    evaluationSummary: LegacyEvSummary? = null,
): LegacyBookmarkLectureDto =
    LegacyBookmarkLectureDto(
        id = lecture.id!!.toString(),
        academicYear = language.select(lecture.academicYear, lecture.academicYearEn),
        category = language.select(lecture.category, lecture.categoryEn),
        classPlaceAndTimes = classTimes.map { LegacyClassPlaceAndTimeFullDto(it) },
        classification = language.select(lecture.classification, lecture.classificationEn),
        credit = lecture.credit,
        department = language.select(lecture.department, lecture.departmentEn),
        instructor = language.select(lecture.instructor, lecture.instructorEn),
        lectureNumber = lecture.lectureNumber,
        quota = lecture.quota,
        freshmanQuota = lecture.freshmanQuota,
        remark = language.select(lecture.remark, lecture.remarkEn),
        courseNumber = lecture.courseNumber,
        courseTitle = language.select(lecture.courseTitle, lecture.courseTitleEn),
        snuttEvLecture = evaluationSummary,
        categoryPre2025 = lecture.categoryPre2025,
    )
