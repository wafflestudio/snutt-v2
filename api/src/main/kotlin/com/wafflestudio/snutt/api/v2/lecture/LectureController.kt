package com.wafflestudio.snutt.api.v2.lecture

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.dto.SearchTime
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LectureSearchRequest(
    val year: Int,
    val semester: Int,
    val query: String? = null,
    val classification: List<String>? = null,
    val credit: List<Int>? = null,
    val courseNumber: List<String>? = null,
    val academicYear: List<String>? = null,
    val department: List<String>? = null,
    val category: List<String>? = null,
    val categoryPre2025: List<String>? = null,
    val etcTags: List<String>? = null,
    val times: List<SearchTimeRequest>? = null,
    val timesToExclude: List<SearchTimeRequest>? = null,
    val page: Int = 0,
    val limit: Int = 20,
    val sortBy: String? = null,
)

data class SearchTimeRequest(
    val day: Int,
    val startMinute: Int,
    val endMinute: Int,
)

data class LectureResponse(
    val id: String,
    val year: Int,
    val semester: Semester,
    val courseNumber: String,
    val lectureNumber: String,
    val courseTitle: String,
    val instructor: String?,
    val department: String?,
    val academicYear: String?,
    val category: String?,
    val categoryPre2025: String?,
    val classification: String?,
    val credit: Int,
    val quota: Int,
    val freshmanQuota: Int?,
    val remark: String?,
    val classPlaceAndTime: List<ClassPlaceAndTimeResponse>,
    val evSummary: LectureEvSummaryResponse?,
)

data class LectureEvSummaryResponse(
    val avgRating: Double?,
    val evalCount: Long,
)

data class ClassPlaceAndTimeResponse(
    val day: DayOfWeek,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
)

private fun Lecture.toResponse(
    classTimes: List<com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime>,
    language: com.wafflestudio.snutt.core.common.client.Language,
    evSummary: LectureEvSummaryResponse? = null,
) = LectureResponse(
    id = externalId,
    year = year,
    semester = semester,
    courseNumber = courseNumber,
    lectureNumber = lectureNumber,
    courseTitle = language.select(courseTitle, courseTitleEn),
    instructor = language.select(instructor, instructorEn),
    department = language.select(department, departmentEn),
    academicYear = language.select(academicYear, academicYearEn),
    category = language.select(category, categoryEn),
    categoryPre2025 = categoryPre2025,
    classification = language.select(classification, classificationEn),
    credit = credit,
    quota = quota,
    freshmanQuota = freshmanQuota,
    remark = language.select(remark, remarkEn),
    classPlaceAndTime = classTimes.map { it.toResponse() },
    evSummary = evSummary,
)

private fun ClassPlaceAndTime.toResponse() =
    ClassPlaceAndTimeResponse(
        day = day,
        place = place,
        startMinute = startMinute,
        endMinute = endMinute,
    )

@RestController
@RequestMapping("/v2/lectures")
class LectureController(
    private val lectureService: LectureService,
    private val evaluationService: EvaluationService,
) {
    @Public
    @PostMapping("/search")
    fun searchLectures(
        @RequestBody request: LectureSearchRequest,
        @RequestAttribute clientInfo: ClientInfo,
    ): List<LectureResponse> {
        val criteria =
            LectureSearchCriteria(
                year = request.year,
                semester = parseSemester(request.semester),
                language = clientInfo.language,
                query = request.query,
                classification = request.classification,
                credit = request.credit,
                courseNumber = request.courseNumber,
                academicYear = request.academicYear,
                department = request.department,
                category = request.category,
                categoryPre2025 = request.categoryPre2025,
                etcTags = request.etcTags,
                times = request.times?.map { parseSearchTime(it) },
                timesToExclude = request.timesToExclude?.map { parseSearchTime(it) },
                offset = request.page * request.limit.toLong(),
                limit = request.limit,
                sort = LectureSort.getOfName(request.sortBy) ?: LectureSort.DEFAULT,
            )
        val lectures = lectureService.search(criteria)
        val summaries = evaluationService.findSummariesByLectureIds(lectures.mapNotNull { it.id })
        val classTimesMap = lectureService.classTimesByLectureId(lectures.mapNotNull { it.id })
        return lectures.map { lecture ->
            val classTimes = classTimesMap[lecture.id].orEmpty()
            val evSummary =
                summaries[lecture.id]?.let { LectureEvSummaryResponse(avgRating = it.avgRating, evalCount = it.evalCount) }
            lecture.toResponse(classTimes, clientInfo.language, evSummary)
        }
    }

    private fun parseSemester(value: Int): Semester = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)

    private fun parseSearchTime(time: SearchTimeRequest): SearchTime {
        val day =
            DayOfWeek.getOfValue(time.day) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        return SearchTime(day = day, startMinute = time.startMinute, endMinute = time.endMinute)
    }
}
