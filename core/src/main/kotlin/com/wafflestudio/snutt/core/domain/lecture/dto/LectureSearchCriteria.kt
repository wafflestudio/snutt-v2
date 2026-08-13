package com.wafflestudio.snutt.core.domain.lecture.dto

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester

// v1 SortCriteria(fullName)와 동일한 값 — 클라이언트가 sortBy로 되돌려 보낸다
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

// v1 SearchDto의 v2 대응 (PLAN.md §7 M2). offset/limit 페이지네이션은 v1 검색 계약 유지
data class LectureSearchCriteria(
    val year: Int,
    val semester: Semester,
    val language: com.wafflestudio.snutt.core.common.client.Language = com.wafflestudio.snutt.core.common.client.Language.KO,
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
