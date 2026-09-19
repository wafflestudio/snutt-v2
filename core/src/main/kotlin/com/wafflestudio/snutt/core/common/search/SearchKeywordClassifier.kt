package com.wafflestudio.snutt.core.common.search

import com.wafflestudio.snutt.core.common.client.Language

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

object SearchKeywordClassifier {
    val GRADUATE_YEARS = listOf("석사", "박사", "석박사통합")

    private val placePattern = Regex("""^(?:|#|\*)\d+(?:-\d+|-[a-zA-Z])?-[a-zA-Z]?\d+[a-zA-Z]?(?:-\d+)?$""")
    private val buildingPattern = Regex("""^(?:|#|\*)\d+(?:-\d+)?동$""")
    private val graduateKeywords = listOf("석박", "대학원")
    private val undergraduateKeywords = listOf("학부", "학사")
    private val englishLectureKeywords = listOf("영강", "영어강의")
    private val militaryLeaveKeywords = listOf("군휴학", "군휴학원격")

    fun classify(
        keyword: String,
        language: Language,
    ): KeywordIntent =
        when {
            keyword.isEmpty() -> KeywordIntent.Empty
            language == Language.EN -> KeywordIntent.Plain(keyword)
            keyword == "전공" -> KeywordIntent.Major
            keyword in graduateKeywords -> KeywordIntent.Graduate
            keyword in undergraduateKeywords -> KeywordIntent.Undergraduate
            keyword == "체육" -> KeywordIntent.PhysicalEducation
            keyword in englishLectureKeywords -> KeywordIntent.EnglishLecture
            keyword in militaryLeaveKeywords -> KeywordIntent.MilitaryLeave
            keyword == "권장과목" -> KeywordIntent.Recommended
            placePattern.matches(keyword) || buildingPattern.matches(keyword) ->
                KeywordIntent.Place(keyword.replace("동", "").uppercase())

            keyword.hasKorean() -> KeywordIntent.Fuzzy(keyword)
            else -> KeywordIntent.Plain(keyword)
        }
}

fun String.hasKorean(): Boolean = any { it in '가'..'힣' }
