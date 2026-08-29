package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseSearchService
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class LegacyTakenLecturesResponse(
    val content: List<LegacyTakenLectureDto>,
)

data class LegacyTakenLectureDto(
    val id: Long?,
    val semesterLectureId: String,
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
) {
    @RequestMapping(
        value = ["/users/me/lectures/latest"],
        method = [RequestMethod.GET, RequestMethod.POST],
    )
    fun getMyLatestLectures(
        @V1CurrentUser user: User,
        @RequestParam(required = false) filter: String?,
    ): LegacyTakenLecturesResponse =
        LegacyTakenLecturesResponse(
            content =
                takenLectureService
                    .getMyLatestLectures(user.id!!, excludeEvaluated = filter == "no-my-evaluations")
                    .map {
                        LegacyTakenLectureDto(
                            id = it.course.id,
                            semesterLectureId = it.lectureId.toString(),
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
    val evaluationCount: Long,
    val avgRating: Double?,
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
    val evaluationCount: Long,
    val avgRating: Double?,
    val semesterLectures: List<LegacySemesterLectureDto>,
)

data class LegacySemesterLectureDto(
    val id: String,
    val year: Int,
    val semester: Int,
    val myEvaluationExists: Boolean,
)

@RestController
@RequestMapping("/v1/ev-service/v1", "/v1/ev/v1")
class V1CompatCourseSearchController(
    private val courseSearchService: CourseSearchService,
    private val legacySearchTagService: LegacySearchTagService,
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
        val result = courseSearchService.getCourseWithSemesters(courseId, user.id!!)
        val course = result.course
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
            evaluationCount = course.evalCount,
            avgRating = course.avgRating,
            semesterLectures =
                result.semesters.map {
                    LegacySemesterLectureDto(
                        id = it.lectureId.toString(),
                        year = it.year,
                        semester = it.semester.value,
                        myEvaluationExists = it.myEvaluationExists,
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
        evaluationCount = evalCount,
        avgRating = avgRating,
    )
