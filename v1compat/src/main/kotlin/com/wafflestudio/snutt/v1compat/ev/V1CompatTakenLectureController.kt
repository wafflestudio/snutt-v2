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
    ): Map<String, Any?> =
        mapOf(
            "content" to
                takenLectureService
                    .getMyLatestLectures(user.id!!, excludeEvaluated = filter == "no-my-evaluations")
                    .map {
                        mapOf(
                            "id" to it.course.id,
                            "semesterLectureId" to it.lectureExternalId,
                            "title" to it.course.title,
                            "instructor" to it.course.instructor,
                            "department" to it.course.department,
                            "courseNumber" to it.course.courseNumber,
                            "credit" to it.course.credit,
                            "academicYear" to it.course.academicYear,
                            "category" to it.course.category,
                            "classification" to it.course.classification,
                            "takenYear" to it.takenYear,
                            "takenSemester" to it.takenSemester.value,
                        )
                    },
        )
}

@RestController
@RequestMapping("/v1/ev-service/v1", "/v1/ev/v1")
class V1CompatCourseSearchController(
    private val courseSearchService: CourseSearchService,
    private val legacySearchTagService: LegacySearchTagService,
) {
    @GetMapping("/tags/search")
    fun getSearchTags(): Map<String, Any?> = mapOf("tagGroups" to legacySearchTagService.searchTagGroups())

    @GetMapping("/lectures")
    fun searchLectures(
        @RequestParam(required = false, defaultValue = "") query: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false) tags: List<Long>?,
    ): Map<String, Any?> {
        val criteria = legacySearchTagService.toCriteria(query, page, tags.orEmpty())
        return linkedMapOf(
            "content" to courseSearchService.search(criteria).map { it.toLegacyCourse() },
            "totalCount" to courseSearchService.count(criteria),
        )
    }

    @GetMapping("/lectures/{courseId}/semester-lectures")
    fun getSemesterLectures(
        @V1CurrentUser user: User,
        @PathVariable courseId: Long,
    ): Map<String, Any?> {
        val result = courseSearchService.getCourseWithSemesters(courseId, user.id!!)
        return result.course.toLegacyCourse() +
            linkedMapOf(
                "semesterLectures" to
                    result.semesters.map {
                        linkedMapOf(
                            "id" to it.lectureExternalId,
                            "year" to it.year,
                            "semester" to it.semester.value,
                            "myEvaluationExists" to it.myEvaluationExists,
                        )
                    },
            )
    }
}

private fun Course.toLegacyCourse(): Map<String, Any?> =
    linkedMapOf(
        "id" to id,
        "title" to title,
        "instructor" to instructor,
        "department" to department,
        "courseNumber" to courseNumber,
        "credit" to credit,
        "academicYear" to academicYear,
        "category" to category,
        "classification" to classification,
        "evaluationCount" to evalCount,
        "avgRating" to avgRating,
    )
