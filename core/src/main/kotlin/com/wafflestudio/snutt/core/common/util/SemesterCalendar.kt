package com.wafflestudio.snutt.core.common.util

import com.wafflestudio.snutt.core.common.enums.Semester
import java.time.LocalDate
import java.time.ZoneId

object SemesterCalendar {
    data class YearSemester(
        val year: Int,
        val semester: Semester,
    )

    private val KST = ZoneId.of("Asia/Seoul")

    fun current(date: LocalDate = LocalDate.now(KST)): YearSemester? {
        for (year in date.year - 1..date.year) {
            for ((semester, range) in ranges(year)) {
                if (date in range.first..range.second) return YearSemester(year, semester)
            }
        }
        return null
    }

    fun next(date: LocalDate): YearSemester {
        for (year in date.year..date.year + 1) {
            for ((semester, range) in ranges(year)) {
                if (date < range.first) return YearSemester(year, semester)
            }
        }
        return YearSemester(date.year + 1, Semester.SPRING)
    }

    private fun ranges(year: Int): List<Pair<Semester, Pair<LocalDate, LocalDate>>> =
        listOf(
            Semester.SPRING to (LocalDate.of(year, 3, 2) to LocalDate.of(year, 6, 23)),
            Semester.SUMMER to (LocalDate.of(year, 6, 24) to LocalDate.of(year, 8, 7)),
            Semester.AUTUMN to (LocalDate.of(year, 9, 1) to LocalDate.of(year, 12, 19)),
            Semester.WINTER to (LocalDate.of(year, 12, 20) to LocalDate.of(year + 1, 1, 31)),
        )
}
