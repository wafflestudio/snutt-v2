package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 최근 두 학기 수강 강의 목록. 강의평 작성 대상을 고르는 화면이 쓴다.
 * 강의평 쓰기와 달리 이메일 인증을 요구하지 않는다 (v1 EvServiceController 동일).
 */
@RestController
class TakenLectureController(
    private val takenLectureService: TakenLectureService,
) {
    // filter=no-my-evaluations 이면 이미 평가한 강의를 제외한다
    @GetMapping("/v2/users/me/lectures/latest")
    fun getMyLatestLectures(
        @CurrentUser user: User,
        @RequestParam(required = false) filter: String?,
    ): List<TakenLectureResponse> =
        takenLectureService
            .getMyLatestLectures(user.id!!, excludeEvaluated = filter == "no-my-evaluations")
            .map { it.toResponse() }
}
