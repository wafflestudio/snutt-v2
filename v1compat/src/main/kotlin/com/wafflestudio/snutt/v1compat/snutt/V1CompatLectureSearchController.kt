package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.LectureCategoryPre2025
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.dto.SearchTime
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.v1compat.auth.V1ApiKeyInterceptor
import com.wafflestudio.snutt.v1compat.auth.V1Public
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyClassPlaceAndTimeFullDto
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyEvSummary
import com.wafflestudio.snutt.v1compat.snutt.dto.toLegacyEvSummary
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
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

data class LegacyLectureDto(
    @param:JsonProperty("_id")
    val id: String,
    @param:JsonProperty("academic_year")
    val academicYear: String?,
    val category: String?,
    @param:JsonProperty("class_time_json")
    val classPlaceAndTimes: List<LegacyClassPlaceAndTimeFullDto>,
    val classification: String?,
    val credit: Int,
    val department: String?,
    val instructor: String?,
    @param:JsonProperty("lecture_number")
    val lectureNumber: String,
    val quota: Int,
    val freshmanQuota: Int?,
    val remark: String?,
    val semester: Semester,
    val year: Int,
    @param:JsonProperty("course_number")
    val courseNumber: String,
    @param:JsonProperty("course_title")
    val courseTitle: String,
    val registrationCount: Int,
    val wasFull: Boolean,
    val snuttEvLecture: LegacyEvSummary?,
    val categoryPre2025: String?,
)

private fun Lecture.toLegacy(
    classTimes: List<ClassPlaceAndTime>,
    language: Language,
    evaluationSummary: LegacyEvSummary?,
    status: LectureRegistrationStatus?,
) = LegacyLectureDto(
    id = id!!.toString(),
    academicYear = language.select(academicYear, academicYearEn),
    category = language.select(category, categoryEn),
    classPlaceAndTimes = classTimes.map { LegacyClassPlaceAndTimeFullDto(it) },
    classification = language.select(classification, classificationEn),
    credit = credit,
    department = language.select(department, departmentEn),
    instructor = language.select(instructor, instructorEn),
    lectureNumber = lectureNumber,
    quota = quota,
    freshmanQuota = freshmanQuota,
    remark = language.select(remark, remarkEn),
    semester = semester,
    year = year,
    courseNumber = courseNumber,
    courseTitle = language.select(courseTitle, courseTitleEn),
    registrationCount = status?.registrationCount ?: 0,
    wasFull = status?.wasFull ?: false,
    snuttEvLecture = evaluationSummary,
    categoryPre2025 = categoryPre2025?.let { LectureCategoryPre2025.localize(it, language) },
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
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
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
            lecture.toLegacy(
                classTimesMap[lecture.id].orEmpty(),
                clientInfo.language,
                summaries[lecture.id]?.toLegacyEvSummary(lecture.courseId),
                statuses[lecture.id],
            )
        }
    }
}
