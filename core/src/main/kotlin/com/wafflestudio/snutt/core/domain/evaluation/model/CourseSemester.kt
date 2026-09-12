package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "course_semester")
class CourseSemester(
    val courseId: Long,
    val year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    val semester: Semester,
    var credit: Int,
    var academicYear: String? = null,
    var category: String? = null,
    var classification: String? = null,
    var extraInfo: String? = null,
    var department: String? = null,
    var departmentEn: String? = null,
    var academicYearEn: String? = null,
    var categoryEn: String? = null,
    var classificationEn: String? = null,
    var categoryPre2025: String? = null,
) : BaseEntity()
