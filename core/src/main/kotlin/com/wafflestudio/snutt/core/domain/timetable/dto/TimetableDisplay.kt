package com.wafflestudio.snutt.core.domain.timetable.dto

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeKind
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import java.time.Instant

data class TimetableLectureDisplay(
    val id: Long,
    val lectureId: Long?,
    val courseId: Long?,
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
    val color: ColorSet,
    val customColor: ColorSet?,
    val paletteIndex: Int,
    val themeKind: ThemeKind,
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
    theme: TimetableThemeDisplay,
): TimetableLectureDisplay {
    val overrides = timetableLecture.overrides
    return TimetableLectureDisplay(
        id = checkNotNull(timetableLecture.id),
        lectureId = lecture?.id,
        courseId = lecture?.courseId,
        academicYear = overrides?.academicYear ?: lecture?.academicYear,
        category = overrides?.category ?: lecture?.category,
        categoryPre2025 = overrides?.categoryPre2025 ?: lecture?.categoryPre2025,
        classification = overrides?.classification ?: lecture?.classification,
        courseNumber = lecture?.courseNumber,
        lectureNumber = lecture?.lectureNumber,
        department = lecture?.department,
        quota = lecture?.quota,
        freshmanQuota = lecture?.freshmanQuota,
        courseTitle = overrides?.courseTitle ?: lecture?.courseTitle ?: "",
        instructor = overrides?.instructor ?: lecture?.instructor,
        credit = overrides?.credit ?: lecture?.credit,
        remark = overrides?.remark ?: lecture?.remark,
        classPlaceAndTimes = overrides?.classPlaceAndTimes ?: classTimes,
        color = timetableLecture.customColor ?: theme.colors[timetableLecture.paletteIndex],
        customColor = timetableLecture.customColor,
        paletteIndex = timetableLecture.paletteIndex,
        themeKind = theme.kind,
        courseTitleEn = lecture?.courseTitleEn.takeIf { overrides?.courseTitle == null },
        instructorEn = lecture?.instructorEn.takeIf { overrides?.instructor == null },
        departmentEn = lecture?.departmentEn,
        academicYearEn = lecture?.academicYearEn.takeIf { overrides?.academicYear == null },
        categoryEn = lecture?.categoryEn.takeIf { overrides?.category == null },
        classificationEn = lecture?.classificationEn.takeIf { overrides?.classification == null },
        remarkEn = lecture?.remarkEn.takeIf { overrides?.remark == null },
    )
}

data class TimetableDisplay(
    val timetable: Timetable,
    val theme: TimetableThemeDisplay,
    val lectures: List<TimetableLectureDisplay>,
)

data class TimetableBriefDto(
    val id: Long,
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
    id = checkNotNull(timetable.id),
    year = timetable.year,
    semester = timetable.semester,
    title = timetable.title,
    isPrimary = timetable.isPrimary,
    updatedAt = checkNotNull(timetable.updatedAt),
    totalCredit = totalCredit,
)
