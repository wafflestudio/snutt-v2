package com.wafflestudio.snutt.api.v2.lecture

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.enums.LectureCategoryPre2025
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.dto.SearchTime
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

const val MAX_SEARCH_PAGE_SIZE = 100L

data class LectureSearchRequest(
    val year: Int,
    val semester: Semester,
    val query: String? = null,
    val classification: List<String>? = null,
    val credit: List<Int>? = null,
    val courseNumber: List<String>? = null,
    val academicYear: List<String>? = null,
    val department: List<String>? = null,
    val category: List<String>? = null,
    val categoryPre2025: List<String>? = null,
    val etcTags: List<String>? = null,
    val times: List<SearchTime>? = null,
    val timesToExclude: List<SearchTime>? = null,
    val cursor: String? = null,
    @field:Min(1) @field:Max(MAX_SEARCH_PAGE_SIZE) val limit: Int = 20,
    val sort: String? = null,
)

@RestController
@RequestMapping("/v2/lectures")
class LectureController(
    private val lectureService: LectureService,
) {
    @Public
    @PostMapping("/search")
    fun searchLectures(
        @Valid @RequestBody request: LectureSearchRequest,
        @RequestAttribute clientInfo: ClientInfo,
    ): CursorPage<LectureResponse> {
        val criteria =
            LectureSearchCriteria(
                year = request.year,
                semester = request.semester,
                language = clientInfo.language,
                query = request.query,
                classification = request.classification,
                credit = request.credit,
                courseNumber = request.courseNumber,
                academicYear = request.academicYear,
                department = request.department,
                category = request.category,
                categoryPre2025 = request.categoryPre2025?.map { LectureCategoryPre2025.toKorean(it) },
                etcTags = request.etcTags,
                times = request.times,
                timesToExclude = request.timesToExclude,
                sort = LectureSort.fromParameter(request.sort),
            )
        val page = lectureService.search(criteria, request.cursor, request.limit)
        val classTimesMap = lectureService.classTimesByLectureId(page.content.map { it.lecture.id!! })
        return page.map { row ->
            row.lecture.toResponse(
                classTimesMap[row.lecture.id].orEmpty(),
                clientInfo.language,
                LectureEvSummaryResponse(avgRating = row.avgRating, evalCount = row.evalCount),
            )
        }
    }
}
