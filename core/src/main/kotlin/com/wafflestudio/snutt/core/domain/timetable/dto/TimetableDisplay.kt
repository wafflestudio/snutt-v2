package com.wafflestudio.snutt.core.domain.timetable.dto

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import java.time.Instant

data class TimetableLectureDisplay(
    val id: String,
    val lectureId: String?,
    val academicYear: String?,
    val category: String?,
    val categoryPre2025: String?,
    val classification: String?,
    val courseNumber: String?,
    val lectureNumber: String?,
    val department: String?,
    val quota: Int?,
    val freshmanQuota: Int?,
    val courseTitle: String,
    val instructor: String?,
    val credit: Int?,
    val remark: String?,
    val classPlaceAndTimes: List<ClassPlaceAndTime>,
    val color: ColorSet?,
    val colorIndex: Int,
    val courseTitleEn: String?,
    val instructorEn: String?,
    val departmentEn: String?,
    val academicYearEn: String?,
    val categoryEn: String?,
    val classificationEn: String?,
    val remarkEn: String?,
)

fun TimetableLectureDisplay(
    timetableLecture: TimetableLecture,
    lecture: Lecture?,
    classTimes: List<ClassPlaceAndTime>,
): TimetableLectureDisplay =
    TimetableLectureDisplay(
        id = timetableLecture.externalId,
        lectureId = lecture?.externalId,
        academicYear = timetableLecture.academicYear ?: lecture?.academicYear,
        category = timetableLecture.category ?: lecture?.category,
        categoryPre2025 = timetableLecture.categoryPre2025 ?: lecture?.categoryPre2025,
        classification = timetableLecture.classification ?: lecture?.classification,
        courseNumber = lecture?.courseNumber,
        lectureNumber = lecture?.lectureNumber,
        department = lecture?.department,
        quota = lecture?.quota,
        freshmanQuota = lecture?.freshmanQuota,
        courseTitle = timetableLecture.courseTitle ?: lecture?.courseTitle ?: "",
        instructor = timetableLecture.instructor ?: lecture?.instructor,
        credit = timetableLecture.credit ?: lecture?.credit,
        remark = timetableLecture.remark ?: lecture?.remark,
        classPlaceAndTimes = timetableLecture.classPlaceAndTimes ?: classTimes,
        color = timetableLecture.color,
        colorIndex = timetableLecture.colorIndex,
        courseTitleEn = lecture?.courseTitleEn,
        instructorEn = lecture?.instructorEn,
        departmentEn = lecture?.departmentEn,
        academicYearEn = lecture?.academicYearEn,
        categoryEn = lecture?.categoryEn,
        classificationEn = lecture?.classificationEn,
        remarkEn = lecture?.remarkEn,
    )

data class TimetableDisplay(
    val timetable: Timetable,
    val lectures: List<TimetableLectureDisplay>,
    val themeExternalId: String? = null,
)

data class TimetableBriefDto(
    val id: String,
    val year: Int,
    val semester: Semester,
    val title: String,
    val isPrimary: Boolean,
    val updatedAt: Instant,
    val totalCredit: Int,
)

fun TimetableBriefDto(
    timetable: Timetable,
    totalCredit: Int,
) = TimetableBriefDto(
    id = timetable.externalId,
    year = timetable.year,
    semester = timetable.semester,
    title = timetable.title,
    isPrimary = timetable.isPrimary,
    updatedAt = checkNotNull(timetable.updatedAt),
    totalCredit = totalCredit,
)
