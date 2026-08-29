package com.wafflestudio.snutt.core.domain.lecture.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "lecture")
class Lecture(
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
    var courseNumber: String,
    var lectureNumber: String,
    var courseTitle: String,
    var instructor: String? = null,
    var department: String? = null,
    var academicYear: String? = null,
    var category: String? = null,
    var categoryPre2025: String? = null,
    var classification: String? = null,
    var credit: Int = 0,
    var quota: Int = 0,
    var freshmanQuota: Int? = null,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var remark: String? = null,
    var courseTitleEn: String? = null,
    var instructorEn: String? = null,
    var departmentEn: String? = null,
    var academicYearEn: String? = null,
    var categoryEn: String? = null,
    var classificationEn: String? = null,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var remarkEn: String? = null,
    @Column(name = "course_id")
    var courseId: Long? = null,
) : BaseEntity() {
    fun copyMetadataFrom(other: Lecture) {
        academicYear = other.academicYear
        category = other.category
        categoryPre2025 = other.categoryPre2025
        classification = other.classification
        credit = other.credit
        department = other.department
        instructor = other.instructor
        quota = other.quota
        freshmanQuota = other.freshmanQuota
        remark = other.remark
        courseTitleEn = other.courseTitleEn
        instructorEn = other.instructorEn
        departmentEn = other.departmentEn
        academicYearEn = other.academicYearEn
        categoryEn = other.categoryEn
        classificationEn = other.classificationEn
        remarkEn = other.remarkEn
        courseTitle = other.courseTitle
    }
}
