package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseSearchService
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenCourseInput
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

data class LegacyTakenLecturesResponse(
    val content: List<LegacyTakenLectureDto>,
)

data class LegacyTakenCourseInput(
    val year: Int,
    val semester: Int,
    val instructor: String?,
    val courseNumber: String?,
)

data class LegacyTakenLectureDto(
    val id: Long?,
    val title: String,
    val instructor: String,
    val department: String?,
    val courseNumber: String,
    val credit: Int?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val takenYear: Int,
    val takenSemester: Int,
)

@RestController
@RequestMapping("/v1/ev-service/v1", "/v1/ev/v1")
class V1CompatTakenLectureController(
    private val takenLectureService: TakenLectureService,
    private val jsonMapper: JsonMapper,
) {
    @GetMapping("/users/me/lectures/latest")
    fun getMyLatestLectures(
        @V1CurrentUser user: User,
        @RequestParam("snutt_lecture_info", required = false, defaultValue = "[]") inputsJson: String,
        @RequestParam(required = false) filter: String?,
    ): LegacyTakenLecturesResponse {
        val inputs = jsonMapper.readValue(inputsJson, Array<LegacyTakenCourseInput>::class.java).toList()
        return getMyLatestLectures(user, inputs, filter)
    }

    @PostMapping("/users/me/lectures/latest")
    fun postMyLatestLectures(
        @V1CurrentUser user: User,
        @RequestBody inputs: List<LegacyTakenCourseInput>,
        @RequestParam(required = false) filter: String?,
    ): LegacyTakenLecturesResponse = getMyLatestLectures(user, inputs, filter)

    private fun getMyLatestLectures(
        user: User,
        inputs: List<LegacyTakenCourseInput>,
        filter: String?,
    ): LegacyTakenLecturesResponse =
        LegacyTakenLecturesResponse(
            content =
                takenLectureService
                    .getCoursesFromInputs(
                        user.id!!,
                        inputs.map {
                            TakenCourseInput(
                                year = it.year,
                                semester = Semester.getOfValue(it.semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
                                courseNumber = it.courseNumber,
                                instructor = it.instructor,
                            )
                        },
                        excludeEvaluated = filter == "no-my-evaluations",
                    ).map {
                        LegacyTakenLectureDto(
                            id = it.course.id,
                            title = it.course.title,
                            instructor = it.course.instructor,
                            department = it.course.department,
                            courseNumber = it.course.courseNumber,
                            credit = it.course.credit,
                            academicYear = it.course.academicYear,
                            category = it.course.category,
                            classification = it.course.classification,
                            takenYear = it.takenYear,
                            takenSemester = it.takenSemester.value,
                        )
                    },
        )
}

data class LegacySearchTagGroupsResponse(
    val tagGroups: List<LegacyEvTagGroupDto>,
)

data class LegacyCourseSearchResponse(
    val content: List<LegacyCourseDto>,
    val totalCount: Long,
)

data class LegacyCourseDto(
    val id: Long?,
    val title: String,
    val instructor: String,
    val department: String?,
    val courseNumber: String,
    val credit: Int?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val evaluation: LegacyCourseEvaluationSummaryDto,
)

data class LegacyCourseEvaluationSummaryDto(
    val avgRating: Double?,
    val evaluationCount: Long,
)

data class LegacyCourseWithSemestersResponse(
    val id: Long?,
    val title: String,
    val instructor: String,
    val department: String?,
    val courseNumber: String,
    val credit: Int?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val semesterLectures: List<LegacySemesterLectureDto>,
)

data class LegacySemesterLectureDto(
    val id: Long,
    val year: Int,
    val semester: Int,
    val credit: Int,
    val extraInfo: String,
    val academicYear: String,
    val category: String,
    val classification: String,
    val myEvaluationExists: Boolean,
)

@RestController
@RequestMapping("/v1/ev-service/v1", "/v1/ev/v1")
class V1CompatCourseSearchController(
    private val courseSearchService: CourseSearchService,
    private val legacySearchTagService: LegacySearchTagService,
    private val legacySemesterLectureService: LegacySemesterLectureService,
    private val evaluationService: EvaluationService,
) {
    @GetMapping("/tags/search")
    fun getSearchTags(): LegacySearchTagGroupsResponse = LegacySearchTagGroupsResponse(tagGroups = legacySearchTagService.searchTagGroups())

    @GetMapping("/lectures")
    fun searchLectures(
        @RequestParam(required = false, defaultValue = "") query: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false) tags: List<Long>?,
    ): LegacyCourseSearchResponse {
        val criteria = legacySearchTagService.toCriteria(query, page, tags.orEmpty())
        return LegacyCourseSearchResponse(
            content = courseSearchService.search(criteria).map { it.toLegacyCourse() },
            totalCount = courseSearchService.count(criteria),
        )
    }

    @GetMapping("/lectures/{courseId}/semester-lectures")
    fun getSemesterLectures(
        @V1CurrentUser user: User,
        @PathVariable courseId: Long,
    ): LegacyCourseWithSemestersResponse {
        val course = courseSearchService.getCourse(courseId)
        val evaluatedSemesters =
            evaluationService
                .getMyEvaluationsOfCourse(user.id!!, courseId)
                .map { it.evaluation.year to it.evaluation.semester }
                .toSet()
        return LegacyCourseWithSemestersResponse(
            id = course.id,
            title = course.title,
            instructor = course.instructor,
            department = course.department,
            courseNumber = course.courseNumber,
            credit = course.credit,
            academicYear = course.academicYear,
            category = course.category,
            classification = course.classification,
            semesterLectures =
                legacySemesterLectureService.getAll(courseId).map {
                    LegacySemesterLectureDto(
                        id = checkNotNull(it.id),
                        year = it.year,
                        semester = it.semester.value,
                        credit = it.credit,
                        extraInfo = it.extraInfo,
                        academicYear = it.academicYear,
                        category = it.category,
                        classification = it.classification,
                        myEvaluationExists = (it.year to it.semester) in evaluatedSemesters,
                    )
                },
        )
    }
}

private fun Course.toLegacyCourse(): LegacyCourseDto =
    LegacyCourseDto(
        id = id,
        title = title,
        instructor = instructor,
        department = department,
        courseNumber = courseNumber,
        credit = credit,
        academicYear = academicYear,
        category = category,
        classification = classification,
        evaluation = LegacyCourseEvaluationSummaryDto(avgRating = avgRating, evaluationCount = evalCount),
    )
