package com.wafflestudio.snutt.core.domain.coursebook.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.util.SemesterCalendar
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId

data class YearAndSemester(
    val year: Int,
    val semester: Semester,
)

@Service
class SemesterService {
    fun getCurrentYearAndSemester(currentTime: Instant): YearAndSemester? =
        SemesterCalendar.current(currentTime.atZone(KST).toLocalDate())?.let { YearAndSemester(it.year, it.semester) }

    fun getNextYearAndSemester(currentTime: Instant): YearAndSemester =
        SemesterCalendar.next(currentTime.atZone(KST).toLocalDate()).let { YearAndSemester(it.year, it.semester) }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
