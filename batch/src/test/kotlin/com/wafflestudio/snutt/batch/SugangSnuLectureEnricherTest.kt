package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.batch.sugangsnu.SugangLectureRow
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuClassTimeUtils
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureApi
import com.wafflestudio.snutt.batch.sugangsnu.SugangSnuLectureEnricher
import com.wafflestudio.snutt.batch.sugangsnu.data.SugangSnuLectureInfo
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.io.DefaultResourceLoader
import tools.jackson.databind.json.JsonMapper

/**
 * 상세 API(강좌 팝업) enrichment가 실제 응답 픽스처를 올바르게 반영하는지 검증한다.
 * 픽스처는 sugang.snu.ac.kr에서 실제로 내려받은 cc101ajax.action 응답이다.
 */
class SugangSnuLectureEnricherTest {
    private val api = Mockito.mock(SugangSnuLectureApi::class.java)
    private val enricher = SugangSnuLectureEnricher(api, DefaultResourceLoader())
    private val jsonMapper = JsonMapper.builder().findAndAddModules().build()

    private val realInfo: SugangSnuLectureInfo by lazy {
        val body = javaClass.getResourceAsStream("/sugang-lecture-info.json")!!.readBytes().decodeToString()
        jsonMapper.readValue(body, SugangSnuLectureInfo::class.java)
    }

    @Test
    fun `상세 API의 시간과 학과를 반영한다`() {
        Mockito
            .`when`(api.getLectureInfo(2026, Semester.AUTUMN, "100.100", "001"))
            .thenReturn(realInfo)

        val row =
            SugangLectureRow(
                classification = "전필",
                category = "전공필수",
                department = "xlsx학과",
                academicYear = "2학년",
                courseNumber = "100.100",
                lectureNumber = "001",
                courseTitle = "xlsx제목",
                credit = 3,
                instructor = "xlsx교수",
                remark = null,
                quota = 40,
                registrationCount = 10,
                classPlaceAndTimes = emptyList(),
            )
        val enriched = enricher.enrich(2026, Semester.AUTUMN, row)

        // 실제 픽스처 값: 화/목 12:30~13:45, 1-102(무선랜제공 제거)
        assertEquals("한국어연구입문", enriched.courseTitle)
        assertEquals("국어국문학과", enriched.department)
        assertEquals(
            listOf(
                ClassPlaceAndTime(DayOfWeek.TUESDAY, "1-102", 750, 825),
                ClassPlaceAndTime(DayOfWeek.THURSDAY, "1-102", 750, 825),
            ),
            enriched.classPlaceAndTimes,
        )
    }

    @Test
    fun `상세 API의 시간 문자열을 파싱한다`() {
        val times =
            SugangSnuClassTimeUtils.convertTextToClassTimeObject(
                listOf("화(12:30~13:45)", "목(12:30~13:45)"),
                listOf("1-102", "1-102"),
            )
        assertEquals(2, times.size)
        assertEquals(750, times.first().startMinute)
        assertEquals(825, times.first().endMinute)
    }
}
