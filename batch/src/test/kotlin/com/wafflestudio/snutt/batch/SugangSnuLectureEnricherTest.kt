package com.wafflestudio.snutt.batch

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.LECTURE_INFO
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.json
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.status
import com.wafflestudio.snutt.batch.sugangsnu.SugangLectureRow
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuClassTimeUtils
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureEnricher
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpClientErrorException

class SugangSnuLectureEnricherTest : AbstractBatchIntegrationTest() {
    @Autowired
    lateinit var enricher: SugangSnuLectureEnricher

    private val row =
        SugangLectureRow(
            classification = "전선",
            category = "",
            department = "국어국문학과",
            academicYear = "1학년",
            courseNumber = "100.100",
            lectureNumber = "001",
            courseTitle = "한국어연구입문",
            credit = 3,
            instructor = "황선엽",
            remark = null,
            quota = 40,
            freshmanQuota = 10,
            classPlaceAndTimes = emptyList(),
        )

    @Test
    fun `실제 상세 API 응답의 시간과 강의실을 반영하고 무선랜 표기를 뗀다`() {
        sugang.lectureInfo("100.100", "001", json(SugangFixtures.text("lecture-info.json")))

        val enriched = enricher.enrich(2026, Semester.AUTUMN, row)

        assertEquals("한국어연구입문", enriched.courseTitle)
        assertEquals("국어국문학과", enriched.department)
        assertEquals(
            listOf(
                ClassPlaceAndTime(DayOfWeek.TUESDAY, "1-102", 750, 825),
                ClassPlaceAndTime(DayOfWeek.THURSDAY, "1-102", 750, 825),
            ),
            enriched.classPlaceAndTimes,
        )
        wireMock.verify(
            getRequestedFor(urlPathEqualTo(sugang.path(LECTURE_INFO)))
                .withQueryParam("openSchyy", equalTo("2026"))
                .withQueryParam("openShtmFg", equalTo("U000200002"))
                .withQueryParam("openDetaShtmFg", equalTo("U000300001"))
                .withQueryParam("sbjtCd", equalTo("100.100"))
                .withQueryParam("ltNo", equalTo("001")),
        )
    }

    @Test
    fun `상세 API에 정보가 없으면 엑셀 값을 그대로 쓴다`() {
        val withTimes = row.copy(classPlaceAndTimes = listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "1-101", 600, 675)))
        sugang.lectureInfoForAll()

        assertEquals(withTimes, enricher.enrich(2026, Semester.AUTUMN, withTimes))
    }

    @Test
    fun `상세 API의 시간 문자열을 분 단위로 바꾼다`() {
        assertEquals(
            listOf(ClassPlaceAndTime(DayOfWeek.TUESDAY, "1-102", 750, 825), ClassPlaceAndTime(DayOfWeek.THURSDAY, "1-102", 750, 825)),
            SugangSnuClassTimeUtils.convertTextToClassTimeObject(listOf("화(12:30~13:45)", "목(12:30~13:45)"), listOf("1-102", "1-102")),
        )
    }

    @Test
    fun `상세 API가 4xx면 예외를 그대로 던진다`() {
        sugang.lectureInfo("100.100", "001", status(404))

        assertThrows<HttpClientErrorException> { enricher.enrich(2026, Semester.AUTUMN, row) }
    }
}
