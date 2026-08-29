package com.wafflestudio.snutt.core.domain.lecture.dto

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester

enum class LectureSort(
    val fullName: String,
) {
    DEFAULT("기본값"),
    RATING_DESC("평점 높은 순"),
    COUNT_DESC("강의평 많은 순"),
    ;

    companion object {
        fun getOfName(name: String?): LectureSort? = entries.firstOrNull { it.fullName == name }
    }
}

data class SearchTime(
    val day: DayOfWeek,
    val startMinute: Int,
    val endMinute: Int,
)

data class LectureSearchCriteria(
    val year: Int,
    val semester: Semester,
    val language: Language = Language.KO,
    val query: String? = null,
    val classification: List<String>? = null,
    val credit: List<Int>? = null,
    val courseNumber: List<String>? = null,
    val academicYear: List<String>? = null,
    val department: List<String>? = null,
    val category: List<String>? = null,
    val categoryPre2025: List<String>? = null,
    val etcTags: List<String>? = null,
    val times: List<SearchTime>? = null,
    val timesToExclude: List<SearchTime>? = null,
    val offset: Long = 0,
    val limit: Int = 20,
    val sort: LectureSort = LectureSort.DEFAULT,
)
