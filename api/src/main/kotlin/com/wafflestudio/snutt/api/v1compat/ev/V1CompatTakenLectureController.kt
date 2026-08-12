package com.wafflestudio.snutt.api.v1compat.ev

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 구 ev-service의 최근 수강 강의 목록. 강의평 쓰기와 달리 이메일 인증을 요구하지 않는다
 * (v1은 이 경로만 프록시의 handleRouting을 타지 않아 게이트 밖이었다).
 */
@RestController
@RequestMapping("/v1/ev-service/v1", "/ev-service/v1", "/v1/ev/v1")
class V1CompatTakenLectureController(
    private val takenLectureService: TakenLectureService,
) {
    @RequestMapping(
        value = ["/users/me/lectures/latest"],
        method = [RequestMethod.GET, RequestMethod.POST],
    )
    fun getMyLatestLectures(
        @CurrentUser user: User,
        @RequestParam(required = false) filter: String?,
    ): Map<String, Any?> =
        mapOf(
            "content" to
                takenLectureService
                    .getMyLatestLectures(user.id!!, excludeEvaluated = filter == "no-my-evaluations")
                    .map {
                        mapOf(
                            "id" to it.course.id,
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

/**
 * 강의평 탭의 강의 검색과 강의 상세(개설 학기 목록).
 * 구 ev LectureController의 /v1/lectures, /v1/lectures/{id}/semester-lectures 이식.
 */
@RestController
@RequestMapping("/v1/ev-service/v1", "/ev-service/v1", "/v1/ev/v1")
class V1CompatCourseSearchController(
    private val courseSearchService: com.wafflestudio.snutt.core.domain.evaluation.service.CourseSearchService,
) {
    @GetMapping("/lectures")
    fun searchLectures(
        @RequestParam(required = false, defaultValue = "") query: String,
        @RequestParam(required = false) tags: List<Long>?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
    ): Map<String, Any?> {
        val courses = courseSearchService.search(query, tags.orEmpty(), page)
        return linkedMapOf(
            "content" to courses.map { it.toLegacyCourse() },
            "totalCount" to courses.size,
        )
    }

    @GetMapping("/lectures/{courseId}/semester-lectures")
    fun getSemesterLectures(
        @CurrentUser user: User,
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

private fun com.wafflestudio.snutt.core.domain.evaluation.model.Course.toLegacyCourse(): Map<String, Any?> =
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
