package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.CurrentClient
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class TakenLectureController(
    private val takenLectureService: TakenLectureService,
    private val courseNameResolver: CourseNameResolver,
    private val evaluationResponseMapper: EvaluationResponseMapper,
) {
    @GetMapping("/v2/users/me/lectures/latest")
    fun getMyLatestLectures(
        @CurrentUserId userId: Long,
        @RequestParam(defaultValue = "false") excludeEvaluated: Boolean,
        @CurrentClient clientInfo: ClientInfo,
    ): List<TakenLectureResponse> {
        val taken = takenLectureService.getMyLatestLectures(userId, excludeEvaluated)
        val names = courseNameResolver.resolve(taken.map { it.course }, clientInfo.language)
        val evaluations =
            evaluationResponseMapper
                .toResponse(taken.flatMap { it.recentEvaluations }.distinctBy { it.evaluation.id }, clientInfo.language)
                .associateBy { it.id }
        return taken.map { lecture ->
            lecture.toResponse(
                names.getValue(lecture.course.id!!),
                clientInfo.language,
                lecture.recentEvaluations.map { evaluations.getValue(it.evaluation.id!!) },
            )
        }
    }
}
