package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyClassPlaceAndTimeFullDto
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyEvSummary
import com.wafflestudio.snutt.api.v1compat.snutt.dto.toLegacyEvSummary
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.dto.SearchTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// v1 SearchQueryLegacy → v2 검색 criteria. 응답은 v1 LectureDto 형태 (snake_case, _id)
data class LegacySearchQuery(
    val year: Int,
    val semester: Int,
    val title: String? = null,
    val classification: List<String>? = null,
    val credit: List<Int>? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("course_number")
    val courseNumber: List<String>? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("academic_year")
    val academicYear: List<String>? = null,
    val department: List<String>? = null,
    val category: List<String>? = null,
    val times: List<LegacySearchTime>? = null,
    val timesToExclude: List<LegacySearchTime>? = null,
    val etc: List<String>? = null,
    val page: Int = 0,
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
    @com.fasterxml.jackson.annotation.JsonProperty("_id")
    val id: String,
    @com.fasterxml.jackson.annotation.JsonProperty("academic_year")
    val academicYear: String?,
    val category: String?,
    @com.fasterxml.jackson.annotation.JsonProperty("class_time_json")
    val classPlaceAndTimes: List<LegacyClassPlaceAndTimeFullDto>,
    val classification: String?,
    val credit: Int,
    val department: String?,
    val instructor: String?,
    @com.fasterxml.jackson.annotation.JsonProperty("lecture_number")
    val lectureNumber: String,
    val quota: Int,
    @com.fasterxml.jackson.annotation.JsonProperty("freshman_quota")
    val freshmanQuota: Int?,
    val remark: String?,
    val semester: Semester,
    val year: Int,
    @com.fasterxml.jackson.annotation.JsonProperty("course_number")
    val courseNumber: String,
    @com.fasterxml.jackson.annotation.JsonProperty("course_title")
    val courseTitle: String,
    val registrationCount: Int,
    val wasFull: Boolean,
    val snuttEvLecture: LegacyEvSummary?,
    val categoryPre2025: String?,
)

private fun Lecture.toLegacy(
    classTimes: List<com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime>,
    language: Language,
    evSummary: LegacyEvSummary?,
) = LegacyLectureDto(
    id = externalId,
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
    registrationCount = registrationCount,
    wasFull = wasFull,
    snuttEvLecture = evSummary,
    categoryPre2025 = categoryPre2025,
)

@RestController
@Public
@RequestMapping("/v1/search_query", "/search_query")
class V1CompatLectureSearchController(
    private val lectureService: LectureService,
    private val evaluationService: com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService,
) {
    @PostMapping("")
    fun searchLectures(
        @RequestBody query: LegacySearchQuery,
        @RequestAttribute clientInfo: ClientInfo,
    ): List<LegacyLectureDto> {
        val criteria =
            LectureSearchCriteria(
                year = query.year,
                semester = Semester.getOfValue(query.semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
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
                            com.wafflestudio.snutt.core.common.enums.DayOfWeek
                                .getOfValue(it.day)!!,
                            it.startMinute,
                            it.endMinute,
                        )
                    },
                timesToExclude =
                    query.timesToExclude?.map {
                        SearchTime(
                            com.wafflestudio.snutt.core.common.enums.DayOfWeek
                                .getOfValue(it.day)!!,
                            it.startMinute,
                            it.endMinute,
                        )
                    },
                offset = query.page * query.limit.toLong(),
                limit = query.limit,
                sort = LectureSort.getOfName(query.sortCriteria) ?: LectureSort.DEFAULT,
            )
        val lectures = lectureService.search(criteria)
        val summaries = evaluationService.findSummariesByLectureIds(lectures.mapNotNull { it.id })
        val classTimesMap = lectureService.classTimesByLectureId(lectures.mapNotNull { it.id })
        return lectures.map { lecture ->
            lecture.toLegacy(
                classTimesMap[lecture.id].orEmpty(),
                clientInfo.language,
                summaries[lecture.id]?.toLegacyEvSummary(lecture.courseId),
            )
        }
    }
}
