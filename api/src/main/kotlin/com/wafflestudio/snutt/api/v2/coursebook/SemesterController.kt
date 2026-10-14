package com.wafflestudio.snutt.api.v2.coursebook

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.common.util.SemesterCalendar
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class YearAndSemesterResponse(
    val year: Int,
    val semester: Semester,
)

data class SemesterStatusResponse(
    val current: YearAndSemesterResponse?,
    val next: YearAndSemesterResponse,
)

fun YearAndSemester.toResponse() = YearAndSemesterResponse(year = year, semester = semester)

@RestController
@RequestMapping("/v2/semesters")
class SemesterController {
    @Public
    @GetMapping("/status")
    fun getSemesterStatus(): SemesterStatusResponse =
        SemesterStatusResponse(
            current = SemesterCalendar.current()?.toResponse(),
            next = SemesterCalendar.next().toResponse(),
        )
}
