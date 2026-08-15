package com.wafflestudio.snutt.api.lecture

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort

data class ReferenceClassTime(
    val day: DayOfWeek,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
)

data class ReferenceLecture(
    val id: Long,
    val year: Int,
    val semester: Semester,
    val academicYear: String?,
    val category: String?,
    val categoryPre2025: String?,
    val classification: String?,
    val credit: Int,
    val department: String?,
    val instructor: String?,
    val lectureNumber: String,
    val remark: String?,
    val courseNumber: String,
    val courseTitle: String,
    val classPlaceAndTimes: List<ReferenceClassTime>,
    val avgRating: Double?,
    val evalCount: Long,
)

object LectureSearchReference {
    private val placeRegex = """^(?:|#|\*)\d+(?:-\d+|-[a-zA-Z])?-[a-zA-Z]?\d+[a-zA-Z]?(?:-\d+)?$""".toRegex()
    private val buildingRegex = """^(?:|#|\*)\d+(?:-\d+)?동$""".toRegex()

    fun search(
        lectures: List<ReferenceLecture>,
        criteria: LectureSearchCriteria,
    ): List<ReferenceLecture> {
        val filtered =
            lectures.filter {
                it.year == criteria.year && it.semester == criteria.semester && matches(it, criteria)
            }
        val sorted =
            when (criteria.sort) {
                LectureSort.DEFAULT -> filtered.sortedBy { it.id }
                LectureSort.RATING_DESC ->
                    filtered.sortedWith(compareByDescending<ReferenceLecture> { it.avgRating }.thenBy { it.id })

                LectureSort.COUNT_DESC ->
                    filtered.sortedWith(compareByDescending<ReferenceLecture> { it.evalCount }.thenBy { it.id })
            }
        return sorted.drop(criteria.offset.toInt()).take(criteria.limit)
    }

    private fun matches(
        lecture: ReferenceLecture,
        criteria: LectureSearchCriteria,
    ): Boolean {
        criteria.classification?.takeIf { it.isNotEmpty() }?.let { if (lecture.classification !in it) return false }
        criteria.credit?.takeIf { it.isNotEmpty() }?.let { if (lecture.credit !in it) return false }
        criteria.courseNumber?.takeIf { it.isNotEmpty() }?.let { if (lecture.courseNumber !in it) return false }
        criteria.academicYear?.takeIf { it.isNotEmpty() }?.let { if (lecture.academicYear !in it) return false }
        criteria.department?.takeIf { it.isNotEmpty() }?.let { if (lecture.department !in it) return false }
        val category = criteria.category
        if (category?.isNotEmpty() == true && (lecture.category == null || lecture.category !in category)) return false
        val categoryPre2025 = criteria.categoryPre2025
        if (categoryPre2025?.isNotEmpty() == true &&
            (lecture.categoryPre2025 == null || lecture.categoryPre2025 !in categoryPre2025)
        ) {
            return false
        }

        criteria.etcTags.orEmpty().forEach { etcTag ->
            val matches =
                when (etcTag) {
                    "E" -> lecture.remark?.contains("ⓔ") == true
                    "MO" -> lecture.remark?.contains("ⓜⓞ") == true
                    "R" -> lecture.remark?.contains("권장과목") == true
                    else -> true
                }
            if (!matches) return false
        }

        criteria.times?.takeIf { it.isNotEmpty() }?.let { times ->
            if (lecture.classPlaceAndTimes.isEmpty()) return false
            val hasUncovered =
                lecture.classPlaceAndTimes.any { classTime ->
                    times.all { time ->
                        classTime.day != time.day ||
                            classTime.startMinute < time.startMinute ||
                            classTime.endMinute > time.endMinute
                    }
                }
            if (hasUncovered) return false
        }

        criteria.timesToExclude?.takeIf { it.isNotEmpty() }?.let { timesToExclude ->
            if (lecture.classPlaceAndTimes.isEmpty()) return false
            val hasOverlap =
                lecture.classPlaceAndTimes.any { classTime ->
                    timesToExclude.any { time ->
                        classTime.day == time.day &&
                            classTime.startMinute < time.endMinute &&
                            classTime.endMinute > time.startMinute
                    }
                }
            if (hasOverlap) return false
        }

        criteria.query?.split(' ')?.forEach { keyword ->
            if (!keywordPredicate(lecture, keyword)) return false
        }
        return true
    }

    private fun keywordPredicate(
        lecture: ReferenceLecture,
        keyword: String,
    ): Boolean =
        when {
            keyword.isEmpty() -> true
            keyword == "전공" -> lecture.classification in listOf("전선", "전필")
            keyword in listOf("석박", "대학원") -> lecture.academicYear in listOf("석사", "박사", "석박사통합")
            keyword in listOf("학부", "학사") -> lecture.academicYear !in listOf("석사", "박사", "석박사통합")
            keyword == "체육" -> lecture.category == "체육"
            keyword in listOf("영강", "영어강의") -> lecture.remark?.contains("ⓔ") == true
            keyword in listOf("군휴학", "군휴학원격") -> lecture.remark?.contains("ⓜⓞ") == true
            keyword == "권장과목" -> lecture.remark?.contains("권장과목") == true
            placeRegex.matches(keyword) || buildingRegex.matches(keyword) -> {
                val placeKeyword = keyword.replace("동", "").uppercase()
                lecture.classPlaceAndTimes.any { classTime ->
                    Regex("^${regexEscape(placeKeyword)}-", RegexOption.IGNORE_CASE)
                        .containsMatchIn(classTime.place) ||
                        Regex("^${regexEscape(placeKeyword)}$", RegexOption.MULTILINE)
                            .containsMatchIn(classTime.place)
                }
            }

            keyword.hasKorean() -> koreanKeyword(lecture, keyword)
            else ->
                Regex(regexEscape(keyword), RegexOption.IGNORE_CASE).containsMatchIn(lecture.courseTitle) ||
                    Regex(regexEscape(keyword), RegexOption.IGNORE_CASE).containsMatchIn(lecture.instructor.orEmpty()) ||
                    lecture.courseNumber == keyword ||
                    lecture.lectureNumber == keyword
        }

    private fun koreanKeyword(
        lecture: ReferenceLecture,
        keyword: String,
    ): Boolean {
        val fuzzyKeyword = fuzzyPattern(keyword)
        val fuzzyRegex = Regex(fuzzyKeyword, RegexOption.IGNORE_CASE)
        if (fuzzyRegex.containsMatchIn(lecture.courseTitle)) return true
        if (fuzzyRegex.containsMatchIn(lecture.category.orEmpty())) return true
        if (lecture.instructor == keyword) return true
        if (lecture.academicYear == keyword) return true
        if (lecture.classification == keyword) return true
        return when (keyword.last()) {
            '과', '부' ->
                Regex("^${fuzzyPattern(keyword.dropLast(1))}", RegexOption.IGNORE_CASE)
                    .containsMatchIn(lecture.department.orEmpty())

            '학' -> false
            else ->
                Regex("^$fuzzyKeyword", RegexOption.IGNORE_CASE).containsMatchIn(lecture.department.orEmpty())
        }
    }

    private fun fuzzyPattern(keyword: String): String = keyword.toCharArray().joinToString(".*") { regexEscape(it.toString()) }

    // Kotlin Regex.escape는 \Q..\E(PCRE)를 쓰지만 MySQL ICU는 지원하지 않으므로
    // 백슬래시 이스케이프를 쓴다. 두 엔진 모두 메타문자 리터럴 매칭은 동일하다
    private fun regexEscape(value: String): String =
        value.flatMap { ch -> if (ch in "\\^$.|?*+()[]{}") listOf('\\', ch) else listOf(ch) }.joinToString("")

    private fun String.hasKorean(): Boolean = isNotEmpty() && any { it in '가'..'힣' }
}
