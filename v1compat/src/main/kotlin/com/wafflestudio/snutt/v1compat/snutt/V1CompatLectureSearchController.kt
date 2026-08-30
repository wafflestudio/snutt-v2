package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonProperty
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
    val semester: Int,
    val title: String? = null,
    val classification: List<String>? = null,
    val credit: List<Int>? = null,
    @param:JsonProperty("course_number")
    val courseNumber: List<String>? = null,
    @param:JsonProperty("academic_year")
    val academicYear: List<String>? = null,
    val department: List<String>? = null,
    val category: List<String>? = null,
    val times: List<LegacySearchTime>? = null,
    val timesToExclude: List<LegacySearchTime>? = null,
    val etc: List<String>? = null,
    val page: Int = 0,
    val offset: Long? = null,
    val limit: Int = 20,
    val sortCriteria: String? = null,
    val categoryPre2025: List<String>? = null,
)

data class LegacySearchTime(
    val day: Int,
    val startMinute: Int,
    val endMinute: Int,
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
    @param:JsonProperty("freshman_quota")
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
    categoryPre2025 = categoryPre2025,
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
                semester = Semester.getOfValue(query.semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
                language = clientInfo.language,
                query = query.title,
                classification = query.classification,
                credit = query.credit,
                courseNumber = query.courseNumber,
                academicYear = query.academicYear,
                department = query.department,
                category = query.category,
                categoryPre2025 = query.categoryPre2025,
                etcTags = query.etc,
                times =
                    query.times?.map {
                        SearchTime(
                            DayOfWeek.getOfValue(it.day)
                                ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
                            it.startMinute,
                            it.endMinute,
                        )
                    },
                timesToExclude =
                    query.timesToExclude?.map {
                        SearchTime(
                            DayOfWeek.getOfValue(it.day)
                                ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
                            it.startMinute,
                            it.endMinute,
                        )
                    },
                sort = LectureSort.getOfName(query.sortCriteria) ?: LectureSort.DEFAULT,
            )
        val offset = query.offset ?: query.page * 20L
        if (offset < 0 || query.limit <= 0 || offset > Int.MAX_VALUE - query.limit) {
            throw SnuttException(ErrorType.INVALID_PARAMETER)
        }
        val lectures =
            lectureService
                .search(criteria, null, offset.toInt() + query.limit)
                .content
                .drop(offset.toInt())
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
