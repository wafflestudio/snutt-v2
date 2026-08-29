package com.wafflestudio.snutt.core.domain.evaluation.dto

import com.wafflestudio.snutt.core.domain.coursebook.service.YearAndSemester

data class CourseSearchCriteria(
    val query: String = "",
    val classification: List<String> = emptyList(),
    val credit: List<Int> = emptyList(),
    val academicYear: List<String> = emptyList(),
    val department: List<String> = emptyList(),
    val category: List<String> = emptyList(),
    val yearSemesters: List<YearAndSemester> = emptyList(),
    val page: Int = 0,
)
