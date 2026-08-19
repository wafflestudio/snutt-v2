package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "timetable_lecture")
class TimetableLecture(
    var timetableId: Long,
    var lectureId: Long? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var color: ColorSet? = null,
    var colorIndex: Int = 0,
    @Column(name = "course_title")
    var courseTitle: String? = null,
    var instructor: String? = null,
    var credit: Int? = null,
    @Column(columnDefinition = "TEXT")
    var remark: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "class_place_and_times")
    var classPlaceAndTimes: List<ClassPlaceAndTime>? = null,
    @Column(name = "academic_year")
    var academicYear: String? = null,
    var category: String? = null,
    var classification: String? = null,
    @Column(name = "category_pre2025")
    var categoryPre2025: String? = null,
) : BaseEntity() {
    fun copyFor(targetTimetableId: Long) =
        TimetableLecture(
            timetableId = targetTimetableId,
            lectureId = lectureId,
            color = color,
            colorIndex = colorIndex,
            courseTitle = courseTitle,
            instructor = instructor,
            credit = credit,
            remark = remark,
            classPlaceAndTimes = classPlaceAndTimes,
            academicYear = academicYear,
            category = category,
            classification = classification,
            categoryPre2025 = categoryPre2025,
        )

    fun clearOverrides() {
        courseTitle = null
        instructor = null
        credit = null
        remark = null
        classPlaceAndTimes = null
        academicYear = null
        category = null
        classification = null
        categoryPre2025 = null
    }
}
