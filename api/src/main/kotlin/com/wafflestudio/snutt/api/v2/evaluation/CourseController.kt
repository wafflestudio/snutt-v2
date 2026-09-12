package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.api.auth.EmailVerifiedRequired
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.LectureCategoryPre2025
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.coursebook.service.YearAndSemester
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseSearchService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class CourseResponse(
    val id: Long,
    val title: String,
    val instructor: String,
    val courseNumber: String,
    val evaluation: CourseEvaluationSummaryResponse,
)

data class CourseEvaluationSummaryResponse(
    val avgRating: Double?,
    val count: Long,
)

data class CourseLectureResponse(
    val id: Long,
    val lectureNumber: String,
    val courseTitle: String,
    val credit: Int,
    val academicYear: String?,
    val classification: String?,
    val category: String?,
    val department: String?,
    val year: Int,
    val semester: Semester,
    val myEvaluationExists: Boolean,
)

data class CourseDetailResponse(
    val course: CourseResponse,
    val lectures: List<CourseLectureResponse>,
)

private fun Course.toResponse() =
    CourseResponse(
        id = id!!,
        title = title,
        instructor = instructor,
        courseNumber = courseNumber,
        evaluation = CourseEvaluationSummaryResponse(avgRating = avgRating, count = evalCount),
    )

@RestController
@EmailVerifiedRequired
@RequestMapping("/v2/courses")
class CourseController(
    private val courseSearchService: CourseSearchService,
) {
    @GetMapping("")
    fun searchCourses(
        @CurrentUserId userId: Long,
        @RequestParam(required = false, defaultValue = "") query: String,
        @RequestParam(required = false) classification: List<String>?,
        @RequestParam(required = false) department: List<String>?,
        @RequestParam(required = false) academicYear: List<String>?,
        @RequestParam(required = false) credit: List<Int>?,
        @RequestParam(required = false) category: List<String>?,
        @RequestParam(required = false) categoryPre2025: List<String>?,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) semester: Int?,
        @RequestParam(required = false) cursor: String?,
        @RequestAttribute clientInfo: ClientInfo,
    ): CursorPage<CourseResponse> {
        if ((year == null) != (semester == null)) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val yearSemesters =
            if (year != null && semester != null) {
                val parsed = Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
                listOf(YearAndSemester(year, parsed))
            } else {
                emptyList()
            }
        val page =
            courseSearchService.search(
                CourseSearchCriteria(
                    query = query,
                    language = clientInfo.language,
                    classification = classification.orEmpty(),
                    department = department.orEmpty(),
                    academicYear = academicYear.orEmpty(),
                    credit = credit.orEmpty(),
                    category = category.orEmpty(),
                    categoryPre2025 = categoryPre2025.orEmpty().map(LectureCategoryPre2025::toKorean),
                    yearSemesters = yearSemesters,
                ),
                cursor,
            )
        return page.map { it.toResponse() }
    }

    @GetMapping("/{courseId}")
    fun getCourse(
        @CurrentUserId userId: Long,
        @PathVariable courseId: Long,
        @RequestAttribute clientInfo: ClientInfo,
    ): CourseDetailResponse {
        val result = courseSearchService.getCourseWithLectures(courseId, userId)
        return CourseDetailResponse(
            course = result.course.toResponse(),
            lectures =
                result.lectures.map { display ->
                    val lecture = display.lecture
                    val language = clientInfo.language
                    CourseLectureResponse(
                        id = lecture.id!!,
                        lectureNumber = lecture.lectureNumber,
                        courseTitle = language.select(lecture.courseTitle, lecture.courseTitleEn),
                        credit = lecture.credit,
                        academicYear = language.select(lecture.academicYear, lecture.academicYearEn),
                        classification = language.select(lecture.classification, lecture.classificationEn),
                        category = language.select(lecture.category, lecture.categoryEn),
                        department = language.select(lecture.department, lecture.departmentEn),
                        year = lecture.year,
                        semester = lecture.semester,
                        myEvaluationExists = display.myEvaluationExists,
                    )
                },
        )
    }
}
