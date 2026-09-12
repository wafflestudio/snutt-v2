package com.wafflestudio.snutt.core.domain.evaluation.dto

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.domain.coursebook.service.YearAndSemester

enum class CourseFilterScope {
    COURSE,
    SEMESTER,
}

data class CourseSearchCriteria(
    val query: String = "",
    val language: Language = Language.KO,
    val filterScope: CourseFilterScope = CourseFilterScope.SEMESTER,
    val classification: List<String> = emptyList(),
    val credit: List<Int> = emptyList(),
    val academicYear: List<String> = emptyList(),
    val department: List<String> = emptyList(),
    val courseDepartment: List<String> = emptyList(),
    val category: List<String> = emptyList(),
    val categoryPre2025: List<String> = emptyList(),
    val yearSemesters: List<YearAndSemester> = emptyList(),
)

data class CourseSearchCursor(
    val evalCount: Long,
    val courseId: Long,
)
