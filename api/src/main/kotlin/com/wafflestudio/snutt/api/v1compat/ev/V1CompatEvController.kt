package com.wafflestudio.snutt.api.v1compat.ev

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.auth.EmailVerifiedRequired
import com.wafflestudio.snutt.api.v2.evaluation.EvaluationResponse
import com.wafflestudio.snutt.api.v2.evaluation.toEvaluationResponse
import com.wafflestudio.snutt.api.v2.evaluation.toEvaluationResponsePage
import com.wafflestudio.snutt.api.v2.evaluation.toEvaluationResponses
import com.wafflestudio.snutt.api.v2.tag.TagGroupResponse
import com.wafflestudio.snutt.api.v2.tag.toResponse
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationReportRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationUpdateRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.tag.service.TagService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// v1 ev-service 프록시의 대체: in-process 컨트롤러 + 일반 인증 (PLAN.md §3).
// 경로는 프록시가 제거하던 prefix를 그대로 받는다 (/v1/ev-service/v1/...)
data class LegacyEvaluationWriteRequest(
    val content: String,
    val gradeSatisfaction: Double,
    val teachingSkill: Double,
    val gains: Double,
    val lifeBalance: Double,
    val rating: Double,
)

data class LegacyEvaluationUpdateRequest(
    val content: String? = null,
    val gradeSatisfaction: Double? = null,
    val teachingSkill: Double? = null,
    val gains: Double? = null,
    val lifeBalance: Double? = null,
    val rating: Double? = null,
)

data class LegacyEvaluationReportRequest(
    val content: String,
)

@RestController
@EmailVerifiedRequired
@RequestMapping("/v1/ev-service/v1", "/ev-service/v1", "/v1/ev/v1")
class V1CompatEvController(
    private val evaluationService: EvaluationService,
    private val userRepository: UserRepository,
    private val tagService: TagService,
) {
    @GetMapping("/lectures/{lectureId}/evaluations")
    fun getEvaluationsOfLecture(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<EvaluationResponse> =
        evaluationService.getEvaluationsOfLecture(user.id!!, lectureId, cursor).toEvaluationResponsePage(userRepository)

    @PostMapping("/semester-lectures/{lectureId}/evaluations")
    fun createEvaluation(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
        @RequestBody body: LegacyEvaluationWriteRequest,
    ): EvaluationResponse =
        evaluationService
            .createEvaluation(
                user.id!!,
                lectureId,
                EvaluationWriteRequest(
                    content = body.content,
                    gradeSatisfaction = body.gradeSatisfaction,
                    teachingSkill = body.teachingSkill,
                    gains = body.gains,
                    lifeBalance = body.lifeBalance,
                    rating = body.rating,
                ),
            ).toEvaluationResponse(userRepository)

    @GetMapping("/lectures/{lectureId}/evaluations/users/me")
    fun getMyEvaluationsOfLecture(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
    ): List<EvaluationResponse> = evaluationService.getMyEvaluationsOfLecture(user.id!!, lectureId).toEvaluationResponses(userRepository)

    @GetMapping("/lectures/{lectureId}/evaluation-summary")
    fun getEvaluationSummaryOfLecture(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
    ): Map<String, Any?> {
        val display = evaluationService.getEvaluationSummaryOfLecture(lectureId)
        val lecture = display.lecture
        val averages = display.averages
        return linkedMapOf(
            "id" to lecture.externalId,
            "title" to lecture.courseTitle,
            "instructor" to lecture.instructor,
            "department" to lecture.department,
            "courseNumber" to lecture.courseNumber,
            "credit" to lecture.credit,
            "academicYear" to lecture.academicYear,
            "category" to lecture.category,
            "classification" to lecture.classification,
            "evaluation" to
                linkedMapOf(
                    "avgGradeSatisfaction" to averages?.avgGradeSatisfaction,
                    "avgTeachingSkill" to averages?.avgTeachingSkill,
                    "avgGains" to averages?.avgGains,
                    "avgLifeBalance" to averages?.avgLifeBalance,
                    "avgRating" to averages?.avgRating,
                ),
        )
    }

    @GetMapping("/evaluations/me")
    fun getMyEvaluations(
        @CurrentUser user: User,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<EvaluationResponse> = evaluationService.getMyEvaluations(user.id!!, cursor).toEvaluationResponsePage(userRepository)

    @GetMapping("/evaluations/{evaluationId}")
    fun getEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ): EvaluationResponse = evaluationService.getEvaluation(user.id!!, evaluationId).toEvaluationResponse(userRepository)

    @PatchMapping("/evaluations/{evaluationId}")
    fun updateEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
        @RequestBody body: LegacyEvaluationUpdateRequest,
    ): EvaluationResponse =
        evaluationService
            .updateEvaluation(
                user.id!!,
                evaluationId,
                EvaluationUpdateRequest(
                    content = body.content,
                    gradeSatisfaction = body.gradeSatisfaction,
                    teachingSkill = body.teachingSkill,
                    gains = body.gains,
                    lifeBalance = body.lifeBalance,
                    rating = body.rating,
                ),
            ).toEvaluationResponse(userRepository)

    @DeleteMapping("/evaluations/{evaluationId}")
    fun deleteEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ) {
        evaluationService.deleteEvaluation(user.id!!, evaluationId)
    }

    @PostMapping("/evaluations/{evaluationId}/report")
    fun reportEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
        @RequestBody body: LegacyEvaluationReportRequest,
    ): Long = evaluationService.reportEvaluation(user.id!!, evaluationId, EvaluationReportRequest(content = body.content))

    @PostMapping("/evaluations/{evaluationId}/likes")
    fun likeEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ) {
        evaluationService.likeEvaluation(user.id!!, evaluationId)
    }

    @DeleteMapping("/evaluations/{evaluationId}/likes")
    fun cancelLikeEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ) {
        evaluationService.cancelLikeEvaluation(user.id!!, evaluationId)
    }

    @GetMapping("/tags/main")
    fun getMainTags(
        @CurrentUser user: User,
    ): TagGroupResponse = tagService.getMainTags().toResponse()

    @GetMapping("/tags/main/{tagId}/evaluations")
    fun getMainTagEvaluations(
        @CurrentUser user: User,
        @PathVariable tagId: Long,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<EvaluationResponse> =
        evaluationService.getEvaluationsByTag(user.id!!, tagId, cursor).toEvaluationResponsePage(userRepository)
}
