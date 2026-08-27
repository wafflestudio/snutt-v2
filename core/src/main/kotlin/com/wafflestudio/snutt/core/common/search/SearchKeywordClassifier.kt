package com.wafflestudio.snutt.core.common.search

import com.wafflestudio.snutt.core.common.client.Language

/**
 * 검색 키워드 관습(전공/석박/영강 등)을 단일 정의하는 순수 분류기.
 *
 * 각 리포지토리는 [KeywordIntent]를 자기 스키마 어휘로 변환한다. 예:
 * - lecture.classification: 전선/전필 (수강편람 교과구분 약어)
 * - course.classification: 전선/전필 (snutt-ev LectureClassification enum value, 동일 어휘)
 * - remark: 영강/군휴학/권장과목 표식
 */
sealed interface KeywordIntent {
    data object Major : KeywordIntent

    data object Graduate : KeywordIntent

    data object Undergraduate : KeywordIntent

    data object PhysicalEducation : KeywordIntent

    data object EnglishLecture : KeywordIntent

    data object MilitaryLeave : KeywordIntent

    data object Recommended : KeywordIntent

    data class Place(
        val keyword: String,
    ) : KeywordIntent

    data class Fuzzy(
        val keyword: String,
    ) : KeywordIntent

    data class Plain(
        val keyword: String,
    ) : KeywordIntent

    data object Empty : KeywordIntent
}

class SearchKeywordClassifier(
    private val placePattern: Regex,
    private val buildingPattern: Regex,
) {
    fun classify(
        keyword: String,
        language: Language,
    ): KeywordIntent =
        when {
            keyword.isEmpty() -> KeywordIntent.Empty
            // EN 검색은 스마트검색(특수키워드/학과접미사) 미지원. 단순 매칭만.
            language == Language.EN -> KeywordIntent.Plain(keyword)
            keyword == "전공" -> KeywordIntent.Major
            keyword in GRADUATE_KEYWORDS -> KeywordIntent.Graduate
            keyword in UNDERGRADUATE_KEYWORDS -> KeywordIntent.Undergraduate
            keyword == "체육" -> KeywordIntent.PhysicalEducation
            keyword in ENGLISH_LECTURE_KEYWORDS -> KeywordIntent.EnglishLecture
            keyword in MILITARY_LEAVE_KEYWORDS -> KeywordIntent.MilitaryLeave
            keyword == "권장과목" -> KeywordIntent.Recommended
            placePattern.matches(keyword) || buildingPattern.matches(keyword) ->
                KeywordIntent.Place(keyword.replace("동", "").uppercase())

            keyword.any { it in '가'..'힣' } -> KeywordIntent.Fuzzy(keyword)
            else -> KeywordIntent.Plain(keyword)
        }

    companion object {
        private val GRADUATE_KEYWORDS = listOf("석박", "대학원")
        private val UNDERGRADUATE_KEYWORDS = listOf("학부", "학사")
        private val ENGLISH_LECTURE_KEYWORDS = listOf("영강", "영어강의")
        private val MILITARY_LEAVE_KEYWORDS = listOf("군휴학", "군휴학원격")
    }
}

fun String.hasKorean(): Boolean = isNotEmpty() && any { it in '가'..'힣' }
