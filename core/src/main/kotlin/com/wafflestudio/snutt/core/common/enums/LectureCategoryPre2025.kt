package com.wafflestudio.snutt.core.common.enums

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select

enum class LectureCategoryPre2025(
    val fullName: String,
    val fullNameEn: String,
) {
    FOUNDATION_WRITING("사고와 표현", "Critical Thinking and Writing"),
    FOUNDATION_LANGUAGE("외국어", "Foreign Languages"),
    FOUNDATION_MATH("수량적 분석과 추론", "Mathematical Sciences"),
    FOUNDATION_SCIENCE("과학적 사고와 실험", "Natural Sciences"),
    FOUNDATION_COMPUTER("컴퓨터와 정보 활용", "Computer and Information Science"),

    KNOWLEDGE_LITERATURE("언어와 문학", "Language and Literature"),
    KNOWLEDGE_ART("문화와 예술", "Culture and Art"),
    KNOWLEDGE_HISTORY("역사와 철학", "History and Philosophy"),
    KNOWLEDGE_POLITICS("정치와 경제", "Politics and Economy"),
    KNOWLEDGE_HUMAN("인간과 사회", "Humans and Society"),
    KNOWLEDGE_NATURE("자연과 기술", "Nature and Technology"),
    KNOWLEDGE_LIFE("생명과 환경", "Life and Environment"),

    GENERAL_PHYSICAL("체육", "Physical Education"),
    GENERAL_ART("예술 실기", "Art Practice"),
    GENERAL_COLLEGE("대학과 리더십", "College Life and Leadership"),
    GENERAL_CREATIVITY("창의와 융합", "Creativity and Convergence"),
    GENERAL_KOREAN("한국의 이해", "Korea in the World (Courses in English)"),
    ;

    companion object {
        private val nameMap = entries.flatMap { listOf(it.fullName to it, it.fullNameEn to it) }.toMap()

        fun getOfName(categoryName: String?): LectureCategoryPre2025? = nameMap[categoryName]

        fun localize(
            categoryName: String,
            language: Language,
        ): String = language.select(categoryName, getOfName(categoryName)?.fullNameEn)

        fun toKorean(categoryName: String): String = getOfName(categoryName)?.fullName ?: categoryName
    }
}
