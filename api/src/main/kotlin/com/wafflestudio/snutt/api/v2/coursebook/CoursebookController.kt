package com.wafflestudio.snutt.api.v2.coursebook

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.util.SugangSnuUrlUtils
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class CoursebookResponse(
    val id: String,
    val year: Int,
    val semester: Semester,
    val updatedAt: Long,
)

data class CoursebookOfficialResponse(
    val url: String,
    val proxyUrl: String?,
)

private fun Coursebook.toResponse() =
    CoursebookResponse(
        id = externalId,
        year = year,
        semester = semester,
        updatedAt = checkNotNull(updatedAt).toEpochMilli(),
    )

@RestController
@RequestMapping("/v2/coursebooks")
class CoursebookController(
    private val coursebookService: CoursebookService,
    @param:Value("\${snutt.syllabus-proxy.base-url}") private val syllabusProxyBaseUrl: String,
) {
    @Public
    @GetMapping("")
    fun getCoursebooks(): List<CoursebookResponse> = coursebookService.getCoursebooks().map { it.toResponse() }

    @Public
    @GetMapping("/recent")
    fun getLatestCoursebook(): CoursebookResponse = coursebookService.getLatestCoursebook().toResponse()

    @Public
    @GetMapping("/official")
    fun getCoursebookOfficial(
        @RequestParam year: Int,
        @RequestParam semester: Int,
        @RequestParam courseNumber: String,
        @RequestParam lectureNumber: String,
    ): CoursebookOfficialResponse {
        val semesterValue =
            Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        val syllabusPath =
            SugangSnuUrlUtils.parseSyllabusPath(year, semesterValue, courseNumber, lectureNumber)
        return CoursebookOfficialResponse(
            url = SugangSnuUrlUtils.SUGANG_SNU_BASE_URL + syllabusPath,
            proxyUrl = syllabusProxyBaseUrl.takeIf { it.isNotBlank() }?.plus(syllabusPath),
        )
    }
}
