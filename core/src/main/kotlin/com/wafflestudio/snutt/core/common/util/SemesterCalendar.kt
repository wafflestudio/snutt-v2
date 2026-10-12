package com.wafflestudio.snutt.core.common.util

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import java.time.LocalDate
import java.time.ZoneId

object SemesterCalendar {
    private val KST = ZoneId.of("Asia/Seoul")

    fun current(date: LocalDate = LocalDate.now(KST)): YearAndSemester? {
        for (year in date.year - 1..date.year) {
            for ((semester, range) in ranges(year)) {
                if (date in range.first..range.second) return YearAndSemester(year, semester)
            }
        }
        return null
    }

    fun next(date: LocalDate = LocalDate.now(KST)): YearAndSemester {
        for (year in date.year..date.year + 1) {
            for ((semester, range) in ranges(year)) {
                if (date < range.first) return YearAndSemester(year, semester)
            }
        }
        return YearAndSemester(date.year + 1, Semester.SPRING)
    }

    private fun ranges(year: Int): List<Pair<Semester, Pair<LocalDate, LocalDate>>> =
        listOf(
            Semester.SPRING to (LocalDate.of(year, 3, 2) to LocalDate.of(year, 6, 23)),
            Semester.SUMMER to (LocalDate.of(year, 6, 24) to LocalDate.of(year, 8, 7)),
            Semester.AUTUMN to (LocalDate.of(year, 9, 1) to LocalDate.of(year, 12, 19)),
            Semester.WINTER to (LocalDate.of(year, 12, 20) to LocalDate.of(year + 1, 1, 31)),
        )
}
