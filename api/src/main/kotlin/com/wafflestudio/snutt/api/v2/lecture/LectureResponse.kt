package com.wafflestudio.snutt.api.v2.lecture

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.LectureCategoryPre2025
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture

data class LectureResponse(
    val id: Long,
    val courseId: Long?,
    val year: Int,
    val semester: Semester,
    val courseNumber: String,
    val lectureNumber: String,
    val courseTitle: String,
    val instructor: String?,
    val department: String?,
    val academicYear: String?,
    val category: String?,
    val categoryPre2025: String?,
    val classification: String?,
    val credit: Int,
    val quota: Int,
    val freshmanQuota: Int?,
    val remark: String?,
    val classPlaceAndTimes: List<ClassPlaceAndTime>,
    val evaluationSummary: LectureEvSummaryResponse? = null,
)

data class LectureEvSummaryResponse(
    val avgRating: Double?,
    val evalCount: Long,
)

fun Lecture.toResponse(
    classTimes: List<ClassPlaceAndTime>,
    language: Language,
    evaluationSummary: LectureEvSummaryResponse? = null,
) = LectureResponse(
    id = id!!,
    courseId = courseId,
    year = year,
    semester = semester,
    courseNumber = courseNumber,
    lectureNumber = lectureNumber,
    courseTitle = language.select(courseTitle, courseTitleEn),
    instructor = language.select(instructor, instructorEn),
    department = language.select(department, departmentEn),
    academicYear = language.select(academicYear, academicYearEn),
    category = language.select(category, categoryEn),
    categoryPre2025 = categoryPre2025?.let { LectureCategoryPre2025.localize(it, language) },
    classification = language.select(classification, classificationEn),
    credit = credit,
    quota = quota,
    freshmanQuota = freshmanQuota,
    remark = language.select(remark, remarkEn),
    classPlaceAndTimes = classTimes,
    evaluationSummary = evaluationSummary,
)
