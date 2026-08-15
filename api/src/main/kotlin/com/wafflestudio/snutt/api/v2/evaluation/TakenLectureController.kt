package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class TakenLectureController(
    private val takenLectureService: TakenLectureService,
) {
    @GetMapping("/v2/users/me/lectures/latest")
    fun getMyLatestLectures(
        @CurrentUser user: User,
        @RequestParam(required = false) filter: String?,
    ): List<TakenLectureResponse> =
        takenLectureService
            .getMyLatestLectures(user.id!!, excludeEvaluated = filter == "no-my-evaluations")
            .map { it.toResponse() }
}
