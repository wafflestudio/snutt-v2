package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "course_semester")
class CourseSemester(
    val courseId: Long,
    val year: Int,
    val semester: Semester,
    var credit: Int,
    var academicYear: String? = null,
    var category: String? = null,
    var classification: String? = null,
    var extraInfo: String? = null,
) : BaseEntity()
