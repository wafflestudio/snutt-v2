package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.common.enums.Semester

data class EvaluatedCourseSemester(
    val courseId: Long,
    val year: Int,
    val semester: Semester,
)
