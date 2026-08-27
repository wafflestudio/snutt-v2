package com.wafflestudio.snutt.core.common.search

import com.wafflestudio.snutt.core.common.client.Language
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchKeywordClassifierTest {
    private val classifier =
        SearchKeywordClassifier(
            placePattern = Regex("""^(?:|#|\*)\d+(?:-\d+|-[a-zA-Z])?-[a-zA-Z]?\d+[a-zA-Z]?(?:-\d+)?$"""),
            buildingPattern = Regex("""^(?:|#|\*)\d+(?:-\d+)?동$"""),
        )

    @Test
    fun emptyKeyword() {
        assertThat(classifier.classify("", Language.KO)).isEqualTo(KeywordIntent.Empty)
    }

    @Test
    fun specialKeywords() {
        assertThat(classifier.classify("전공", Language.KO)).isEqualTo(KeywordIntent.Major)
        assertThat(classifier.classify("석박", Language.KO)).isEqualTo(KeywordIntent.Graduate)
        assertThat(classifier.classify("대학원", Language.KO)).isEqualTo(KeywordIntent.Graduate)
        assertThat(classifier.classify("학부", Language.KO)).isEqualTo(KeywordIntent.Undergraduate)
        assertThat(classifier.classify("체육", Language.KO)).isEqualTo(KeywordIntent.PhysicalEducation)
        assertThat(classifier.classify("영강", Language.KO)).isEqualTo(KeywordIntent.EnglishLecture)
        assertThat(classifier.classify("영어강의", Language.KO)).isEqualTo(KeywordIntent.EnglishLecture)
        assertThat(classifier.classify("군휴학", Language.KO)).isEqualTo(KeywordIntent.MilitaryLeave)
        assertThat(classifier.classify("권장과목", Language.KO)).isEqualTo(KeywordIntent.Recommended)
    }

    @Test
    fun enKeywordIsPlain() {
        assertThat(classifier.classify("전공", Language.EN)).isEqualTo(KeywordIntent.Plain("전공"))
        assertThat(classifier.classify("영강", Language.EN)).isEqualTo(KeywordIntent.Plain("영강"))
    }

    @Test
    fun placeAndBuilding() {
        assertThat(classifier.classify("301-101", Language.KO)).isEqualTo(KeywordIntent.Place("301-101"))
        assertThat(classifier.classify("302동", Language.KO)).isEqualTo(KeywordIntent.Place("302"))
        assertThat(classifier.classify("#302-1", Language.KO)).isEqualTo(KeywordIntent.Place("#302-1"))
    }

    @Test
    fun koreanKeywordIsFuzzy() {
        assertThat(classifier.classify("컴퓨터공학", Language.KO)).isEqualTo(KeywordIntent.Fuzzy("컴퓨터공학"))
        assertThat(classifier.classify("수학", Language.KO)).isEqualTo(KeywordIntent.Fuzzy("수학"))
    }

    @Test
    fun nonKoreanIsPlain() {
        assertThat(classifier.classify("algorithm", Language.KO)).isEqualTo(KeywordIntent.Plain("algorithm"))
        assertThat(classifier.classify("301", Language.KO)).isEqualTo(KeywordIntent.Plain("301"))
    }
}
