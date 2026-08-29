package com.wafflestudio.snutt.api.v2.coursebook

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.service.SemesterService
import com.wafflestudio.snutt.core.domain.coursebook.service.YearAndSemester
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class YearAndSemesterResponse(
    val year: Int,
    val semester: Semester,
)

data class SemesterStatusResponse(
    val current: YearAndSemesterResponse?,
    val next: YearAndSemesterResponse,
)

private fun YearAndSemester.toResponse() = YearAndSemesterResponse(year = year, semester = semester)

@RestController
@RequestMapping("/v2/semesters")
class SemesterController(
    private val semesterService: SemesterService,
) {
    @Public
    @GetMapping("/status")
    fun getSemesterStatus(): SemesterStatusResponse {
        val now = Instant.now()
        return SemesterStatusResponse(
            current = semesterService.getCurrentYearAndSemester(now)?.toResponse(),
            next = semesterService.getNextYearAndSemester(now).toResponse(),
        )
    }
}
