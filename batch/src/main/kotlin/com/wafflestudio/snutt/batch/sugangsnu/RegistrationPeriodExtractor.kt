package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationPhase
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationTimeSlot
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class RegistrationPeriodExtractor(
    private val sugangSnuLectureApi: SugangSnuLectureApi,
    private val semesterRegistrationPeriodService: SemesterRegistrationPeriodService,
) {
    fun extract(
        year: Int,
        semester: Semester,
    ) {
        val table =
            Jsoup
                .parse(sugangSnuLectureApi.getMainPageHtml())
                .select(".table-con table")
                .first() ?: throw IllegalStateException("수강신청 일정 표를 찾지 못했다")
        val newDates = parseRegistrationDates(table)
        val existingDates =
            semesterRegistrationPeriodService
                .getByYearAndSemester(year, semester)
                ?.registrationPeriodList
                ?.associateBy { it.date }
                .orEmpty()
        val merged = (existingDates + newDates).values.sortedBy { it.date }
        semesterRegistrationPeriodService.upsert(year, semester, merged)
    }

    private fun parseRegistrationDates(table: Element): Map<LocalDate, RegistrationDate> =
        table
            .select("tbody > tr")
            .mapNotNull { row ->
                val typeText = row.select("th[data-th=구분] span").text().trim()
                val phase = parseRegistrationPhase(typeText) ?: return@mapNotNull null
                val (startDate, endDate) = parseDateRange(row.select("td[data-th=일자]").text())
                (startDate.toEpochDay()..endDate.toEpochDay()).map { epochDay ->
                    LocalDate.ofEpochDay(epochDay) to
                        RegistrationDate(
                            date = LocalDate.ofEpochDay(epochDay),
                            vacantSeatRegistrationTimes = vacantSeatRegistrationTimes(typeText),
                            phase = phase,
                        )
                }
            }.flatten()
            .toMap()

    private fun parseRegistrationPhase(typeText: String): RegistrationPhase? =
        when {
            typeText.contains("예비") -> null
            typeText.contains("전산확정") -> null
            typeText.contains("정원외") -> null
            typeText.contains("수강취소") -> null
            typeText.contains("장바구니") -> null
            typeText.contains("신입생") && typeText.contains("선착순") -> RegistrationPhase.FRESHMAN
            typeText.contains("수강신청변경") -> RegistrationPhase.COURSE_CHANGE
            typeText.contains("선착순") -> RegistrationPhase.CURRENT_STUDENT
            else -> null
        }

    private fun vacantSeatRegistrationTimes(typeText: String): List<RegistrationTimeSlot> =
        if (typeText.contains("수강신청변경")) {
            listOf(
                RegistrationTimeSlot(startMinute = 10 * 60, endMinute = 11 * 60),
                RegistrationTimeSlot(startMinute = 13 * 60, endMinute = 14 * 60),
                RegistrationTimeSlot(startMinute = 17 * 60, endMinute = 18 * 60),
            )
        } else {
            listOf(
                RegistrationTimeSlot(startMinute = 10 * 60, endMinute = 11 * 60),
                RegistrationTimeSlot(startMinute = 13 * 60, endMinute = 14 * 60),
                RegistrationTimeSlot(startMinute = 15 * 60, endMinute = 16 * 60),
            )
        }

    // "2026-01-30(금) ~ 2026-02-02(월)" 형식
    private fun parseDateRange(dateText: String): Pair<LocalDate, LocalDate> {
        val dates = Regex("""\d{4}-\d{2}-\d{2}""").findAll(dateText).map { it.value }.toList()
        val startDate = LocalDate.parse(dates[0])
        val endDate = LocalDate.parse(dates.getOrElse(1) { dates[0] })
        return startDate to endDate
    }
}
