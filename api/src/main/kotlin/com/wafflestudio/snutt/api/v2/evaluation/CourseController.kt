package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.auth.EmailVerifiedRequired
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.coursebook.service.YearAndSemester
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseSearchService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class CourseResponse(
    val id: Long,
    val title: String,
    val instructor: String,
    val department: String?,
    val courseNumber: String,
    val credit: Int?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val evaluation: CourseEvaluationSummaryResponse,
)

data class CourseEvaluationSummaryResponse(
    val avgRating: Double?,
    val count: Long,
)

data class CourseSemesterResponse(
    val lectureId: String,
    val year: Int,
    val semester: Semester,
    val myEvaluationExists: Boolean,
)

data class CourseDetailResponse(
    val course: CourseResponse,
    val semesters: List<CourseSemesterResponse>,
)

private fun Course.toResponse() =
    CourseResponse(
        id = id!!,
        title = title,
        instructor = instructor,
        department = department,
        courseNumber = courseNumber,
        credit = credit,
        academicYear = academicYear,
        category = category,
        classification = classification,
        evaluation = CourseEvaluationSummaryResponse(avgRating = avgRating, count = evalCount),
    )

/**
 * 강의평의 과목 검색. 필터 값은 강의 검색과 같은 어휘(`GET /v2/tags/{year}/{semester}`)를 쓴다.
 */
@RestController
@EmailVerifiedRequired
@RequestMapping("/v2/courses")
class CourseController(
    private val courseSearchService: CourseSearchService,
) {
    @GetMapping("")
    fun searchCourses(
        @CurrentUser user: User,
        @RequestParam(required = false, defaultValue = "") query: String,
        @RequestParam(required = false) classification: List<String>?,
        @RequestParam(required = false) department: List<String>?,
        @RequestParam(required = false) academicYear: List<String>?,
        @RequestParam(required = false) credit: List<Int>?,
        @RequestParam(required = false) category: List<String>?,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) semester: Int?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
    ): List<CourseResponse> {
        val yearSemesters =
            if (year != null && semester != null) {
                val parsed = Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
                listOf(YearAndSemester(year, parsed))
            } else {
                emptyList()
            }
        return courseSearchService
            .search(
                CourseSearchCriteria(
                    query = query,
                    classification = classification.orEmpty(),
                    department = department.orEmpty(),
                    academicYear = academicYear.orEmpty(),
                    credit = credit.orEmpty(),
                    category = category.orEmpty(),
                    yearSemesters = yearSemesters,
                    page = page,
                ),
            ).map { it.toResponse() }
    }

    @GetMapping("/{courseId}")
    fun getCourse(
        @CurrentUser user: User,
        @PathVariable courseId: Long,
    ): CourseDetailResponse {
        val result = courseSearchService.getCourseWithSemesters(courseId, user.id!!)
        return CourseDetailResponse(
            course = result.course.toResponse(),
            semesters =
                result.semesters.map {
                    CourseSemesterResponse(
                        lectureId = it.lectureExternalId,
                        year = it.year,
                        semester = it.semester,
                        myEvaluationExists = it.myEvaluationExists,
                    )
                },
        )
    }
}
