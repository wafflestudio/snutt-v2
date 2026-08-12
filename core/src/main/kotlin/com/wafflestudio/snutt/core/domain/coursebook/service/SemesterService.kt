package com.wafflestudio.snutt.core.domain.coursebook.service

import com.wafflestudio.snutt.core.common.enums.Semester
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class YearAndSemester(
    val year: Int,
    val semester: Semester,
)

// 학기 진행 상태. 학사 일정 고정 구간으로 판정한다 (v1 SemesterService 이식)
@Service
class SemesterService {
    private data class SemesterRange(
        val semester: Semester,
        val year: Int,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private fun semesterSequence(fromYear: Int): Sequence<SemesterRange> =
        generateSequence(fromYear) { it + 1 }
            .flatMap { year ->
                sequenceOf(
                    SemesterRange(Semester.SPRING, year, LocalDate.of(year, 3, 2), LocalDate.of(year, 6, 23)),
                    SemesterRange(Semester.SUMMER, year, LocalDate.of(year, 6, 24), LocalDate.of(year, 8, 7)),
                    SemesterRange(Semester.AUTUMN, year, LocalDate.of(year, 9, 1), LocalDate.of(year, 12, 19)),
                    SemesterRange(Semester.WINTER, year, LocalDate.of(year, 12, 20), LocalDate.of(year + 1, 1, 31)),
                )
            }

    // 방학 등 어느 학기에도 속하지 않는 기간은 null
    fun getCurrentYearAndSemester(currentTime: Instant): YearAndSemester? {
        val today = currentTime.atZone(KST).toLocalDate()
        return semesterSequence(today.year - 1)
            .takeWhile { today >= it.startDate }
            .firstOrNull { today in it.startDate..it.endDate }
            ?.let { YearAndSemester(it.year, it.semester) }
    }

    fun getNextYearAndSemester(currentTime: Instant): YearAndSemester {
        val today = currentTime.atZone(KST).toLocalDate()
        return semesterSequence(today.year)
            .first { today < it.startDate }
            .let { YearAndSemester(it.year, it.semester) }
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
