package com.wafflestudio.snutt.api.v1compat.ev

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import com.wafflestudio.snutt.core.domain.user.model.User
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
