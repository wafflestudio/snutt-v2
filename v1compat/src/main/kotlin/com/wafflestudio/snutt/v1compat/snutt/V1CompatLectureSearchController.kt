package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.CurrentClient
import com.wafflestudio.snutt.core.common.enums.LectureCategoryPre2025
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.dto.SearchTime
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.v1compat.auth.V1Public
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyLectureDto
import com.wafflestudio.snutt.v1compat.snutt.dto.toLegacyEvSummary
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LegacySearchQuery(
    val year: Int,
    val semester: Semester,
    val title: String? = null,
    val classification: List<String>? = null,
    val credit: List<Int>? = null,
    @param:JsonProperty("course_number")
    val courseNumber: List<String>? = null,
    @param:JsonProperty("academic_year")
    val academicYear: List<String>? = null,
    val department: List<String>? = null,
    val category: List<String>? = null,
    val times: List<SearchTime>? = null,
    val timesToExclude: List<SearchTime>? = null,
    val etc: List<String>? = null,
    val page: Int = 0,
    val offset: Long? = null,
    val limit: Int = 20,
    val sortCriteria: String? = null,
    val categoryPre2025: List<String>? = null,
)

@RestController
@V1Public
@RequestMapping("/v1/search_query")
class V1CompatLectureSearchController(
    private val lectureService: LectureService,
    private val evaluationService: EvaluationService,
    private val lectureRegistrationStatusRepository: LectureRegistrationStatusRepository,
) {
    @PostMapping("")
    fun searchLectures(
        @RequestBody query: LegacySearchQuery,
        @CurrentClient clientInfo: ClientInfo,
    ): List<LegacyLectureDto> {
        val criteria =
            LectureSearchCriteria(
                year = query.year,
                semester = query.semester,
                language = clientInfo.language,
                query = query.title,
                classification = query.classification,
                credit = query.credit,
                courseNumber = query.courseNumber,
                academicYear = query.academicYear,
                department = query.department,
                category = query.category,
                categoryPre2025 = query.categoryPre2025?.map { LectureCategoryPre2025.toKorean(it) },
                etcTags = query.etc,
                times = query.times,
                timesToExclude = query.timesToExclude,
                sort = LectureSort.getOfName(query.sortCriteria) ?: LectureSort.DEFAULT,
            )
        val offset = query.offset ?: query.page * 20L
        if (offset !in 0..Int.MAX_VALUE.toLong()) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val lectures = lectureService.searchByOffset(criteria, offset.toInt(), query.limit)
        val lectureIds = lectures.mapNotNull { it.lecture.id }
        val summaries = evaluationService.findSummariesByLectureIds(lectureIds)
        val classTimesMap = lectureService.classTimesByLectureId(lectureIds)
        val statuses = lectureRegistrationStatusRepository.findAllById(lectureIds).associateBy { it.lectureId }
        return lectures.map { row ->
            val lecture = row.lecture
            LegacyLectureDto(
                lecture = lecture,
                classTimes = classTimesMap[lecture.id].orEmpty(),
                language = clientInfo.language,
                evaluationSummary = summaries[lecture.id]?.toLegacyEvSummary(lecture.courseId),
                status = statuses[lecture.id],
            )
        }
    }
}
