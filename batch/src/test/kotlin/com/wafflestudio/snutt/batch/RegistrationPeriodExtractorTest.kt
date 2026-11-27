package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.status
import com.wafflestudio.snutt.batch.sugangsnu.RegistrationPeriodExtractor
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationPhase
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationTimeSlot
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpServerErrorException
import java.time.LocalDate

class RegistrationPeriodExtractorTest : AbstractBatchIntegrationTest() {
    @Autowired
    lateinit var extractor: RegistrationPeriodExtractor

    @Autowired
    lateinit var periodService: SemesterRegistrationPeriodService

    private val existing =
        RegistrationDate(
            date = LocalDate.of(2026, 11, 20),
            vacantSeatRegistrationTimes = listOf(RegistrationTimeSlot(600, 660)),
            phase = RegistrationPhase.CURRENT_STUDENT,
        )

    private fun dates() = periodService.getByYearAndSemester(2026, Semester.WINTER)!!.registrationPeriodList

    @Test
    fun `실제 메인 페이지처럼 수강취소 일정만 있으면 기존 등록일을 그대로 둔다`() {
        periodService.upsert(2026, Semester.WINTER, listOf(existing))
        sugang.mainPage()

        extractor.extract(2026, Semester.WINTER)

        assertEquals(listOf(existing), dates())
    }

    @Test
    fun `선착순·신입생·수강신청변경 일정을 날짜별로 펼치고 나머지는 건너뛴다`() {
        periodService.upsert(2026, Semester.WINTER, listOf(existing))
        sugang.mainPage(
            SugangFixtures.mainPageWithSchedule(
                listOf(
                    "장바구니 담기" to "2026-11-02(월) ~ 2026-11-04(수)",
                    "재학생 선착순 수강신청" to "2026-11-20(금) ~ 2026-11-21(토)",
                    "신입생 선착순 수강신청" to "2026-11-23(월)",
                    "수강신청변경" to "2026-12-01(화)",
                    "수강취소(개강전)" to "2026-12-11(금) ~ 2026-12-21(월)",
                ),
            ),
        )

        extractor.extract(2026, Semester.WINTER)

        val change = listOf(RegistrationTimeSlot(600, 660), RegistrationTimeSlot(780, 840), RegistrationTimeSlot(1020, 1080))
        val normal = listOf(RegistrationTimeSlot(600, 660), RegistrationTimeSlot(780, 840), RegistrationTimeSlot(900, 960))
        assertEquals(
            listOf(
                RegistrationDate(LocalDate.of(2026, 11, 20), normal, RegistrationPhase.CURRENT_STUDENT),
                RegistrationDate(LocalDate.of(2026, 11, 21), normal, RegistrationPhase.CURRENT_STUDENT),
                RegistrationDate(LocalDate.of(2026, 11, 23), normal, RegistrationPhase.FRESHMAN),
                RegistrationDate(LocalDate.of(2026, 12, 1), change, RegistrationPhase.COURSE_CHANGE),
            ),
            dates(),
        )
    }

    @Test
    fun `메인 페이지가 5xx면 실패하고 기존 등록일을 그대로 둔다`() {
        periodService.upsert(2026, Semester.WINTER, listOf(existing))
        sugang.mainPageResponse(status(500))

        assertThrows<HttpServerErrorException> { extractor.extract(2026, Semester.WINTER) }

        assertEquals(listOf(existing), dates())
    }
}
