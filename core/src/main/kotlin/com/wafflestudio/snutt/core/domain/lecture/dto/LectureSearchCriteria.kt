package com.wafflestudio.snutt.core.domain.lecture.dto

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException

enum class LectureSort(
    val fullName: String,
    val fullNameEn: String,
) {
    DEFAULT("기본값", "Default"),
    RATING_DESC("평점 높은 순", "Highest rating"),
    COUNT_DESC("강의평 많은 순", "Most reviews"),
    ;

    companion object {
        // 클라이언트가 언어에 따라 한글/영문 중 무엇을 보내든 받을 수 있도록 둘 다 키로 등록한다
        private val nameMap = entries.flatMap { listOf(it.fullName to it, it.fullNameEn to it) }.toMap()

        fun getOfName(name: String?): LectureSort? = nameMap[name]

        fun fromParameter(value: String?): LectureSort =
            if (value == null) {
                DEFAULT
            } else {
                entries.find { it.name.equals(value, ignoreCase = true) }
                    ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
            }
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
    val sort: LectureSort = LectureSort.DEFAULT,
)

data class LectureSearchCursor(
    val sort: LectureSort,
    val lectureId: Long,
)
