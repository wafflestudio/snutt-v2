package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.auth.EmailVerifiedRequired
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationReportRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationUpdateRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.LectureTakenByUser
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.UserService
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class EvaluationWriteRequestBody(
    @field:NotBlank val content: String,
    val gradeSatisfaction: Double,
    val teachingSkill: Double,
    val gains: Double,
    val lifeBalance: Double,
    val rating: Double,
)

data class TakenLectureResponse(
    val id: Long,
    val lectureId: Long,
    val title: String,
    val instructor: String,
    val courseNumber: String,
    val department: String?,
    val credit: Int?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val takenYear: Int,
    val takenSemester: Semester,
)

internal fun LectureTakenByUser.toResponse() =
    TakenLectureResponse(
        id = requireNotNull(course.id),
        lectureId = lectureId,
        title = course.title,
        instructor = course.instructor,
        courseNumber = course.courseNumber,
        department = course.department,
        credit = course.credit,
        academicYear = course.academicYear,
        category = course.category,
        classification = course.classification,
        takenYear = takenYear,
        takenSemester = takenSemester,
    )

data class EvaluationUpdateRequestBody(
    val content: String? = null,
    val gradeSatisfaction: Double? = null,
    val teachingSkill: Double? = null,
    val gains: Double? = null,
    val lifeBalance: Double? = null,
    val rating: Double? = null,
)

data class EvaluationReportRequestBody(
    @field:NotBlank val content: String,
)

data class EvaluationResponse(
    val id: Long,
    val user: EvaluationUserResponse?,
    val content: String,
    val gradeSatisfaction: Double?,
    val teachingSkill: Double?,
    val gains: Double?,
    val lifeBalance: Double?,
    val rating: Double,
    val likeCount: Long,
    val isHidden: Boolean,
    val isReported: Boolean,
    val isLiked: Boolean,
    val fromSnuev: Boolean,
    val year: Int,
    val semester: Semester,
    val isModifiable: Boolean,
    val isReportable: Boolean,
)

data class EvaluationUserResponse(
    val id: Long,
    val nickname: String,
)

data class LectureEvaluationSummaryResponse(
    val id: Long,
    val title: String,
    val instructor: String?,
    val department: String?,
    val courseNumber: String,
    val credit: Int,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val evaluation: EvaluationAveragesResponse,
)

data class EvaluationAveragesResponse(
    val avgGradeSatisfaction: Double?,
    val avgTeachingSkill: Double?,
    val avgGains: Double?,
    val avgLifeBalance: Double?,
    val avgRating: Double?,
)

data class EvaluationTagResponse(
    val key: String,
    val title: String,
    val description: String,
)

@RestController
@EmailVerifiedRequired
class EvaluationController(
    private val evaluationService: EvaluationService,
    private val userService: UserService,
) {
    @GetMapping("/v2/lectures/{lectureId}/evaluations")
    fun getEvaluationsOfLecture(
        @CurrentUser user: User,
        @PathVariable lectureId: Long,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<EvaluationResponse> =
        evaluationService.getEvaluationsOfLecture(user.id!!, lectureId, cursor).toEvaluationResponsePage(userService)

    @PostMapping("/v2/lectures/{lectureId}/evaluations")
    fun createEvaluation(
        @CurrentUser user: User,
        @PathVariable lectureId: Long,
        @RequestBody body: EvaluationWriteRequestBody,
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
            ).toEvaluationResponse(userService)

    @GetMapping("/v2/lectures/{lectureId}/evaluations/me")
    fun getMyEvaluationsOfLecture(
        @CurrentUser user: User,
        @PathVariable lectureId: Long,
    ): List<EvaluationResponse> = evaluationService.getMyEvaluationsOfLecture(user.id!!, lectureId).toEvaluationResponses(userService)

    @GetMapping("/v2/lectures/{lectureId}/evaluation-summary")
    fun getEvaluationSummaryOfLecture(
        @CurrentUser user: User,
        @PathVariable lectureId: Long,
    ): LectureEvaluationSummaryResponse {
        val display = evaluationService.getEvaluationSummaryOfLecture(lectureId)
        val lecture = display.lecture
        return LectureEvaluationSummaryResponse(
            id = lecture.id!!,
            title = lecture.courseTitle,
            instructor = lecture.instructor,
            department = lecture.department,
            courseNumber = lecture.courseNumber,
            credit = lecture.credit,
            academicYear = lecture.academicYear,
            category = lecture.category,
            classification = lecture.classification,
            evaluation =
                display.averages?.let {
                    EvaluationAveragesResponse(
                        avgGradeSatisfaction = it.avgGradeSatisfaction,
                        avgTeachingSkill = it.avgTeachingSkill,
                        avgGains = it.avgGains,
                        avgLifeBalance = it.avgLifeBalance,
                        avgRating = it.avgRating,
                    )
                } ?: EvaluationAveragesResponse(null, null, null, null, null),
        )
    }

    @GetMapping("/v2/evaluations/me")
    fun getMyEvaluations(
        @CurrentUser user: User,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<EvaluationResponse> = evaluationService.getMyEvaluations(user.id!!, cursor).toEvaluationResponsePage(userService)

    @GetMapping("/v2/evaluations/tags")
    fun getEvaluationTags(
        @CurrentUser user: User,
    ): List<EvaluationTagResponse> =
        EvaluationTag.entries.map { EvaluationTagResponse(key = it.key, title = it.title, description = it.description) }

    @GetMapping("/v2/evaluations/tags/{tagKey}")
    fun getEvaluationsByTag(
        @CurrentUser user: User,
        @PathVariable tagKey: String,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<EvaluationResponse> {
        val tag = EvaluationTag.fromKey(tagKey) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        return evaluationService.getEvaluationsByTag(user.id!!, tag, cursor).toEvaluationResponsePage(userService)
    }

    @GetMapping("/v2/evaluations/{evaluationId}")
    fun getEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ): EvaluationResponse = evaluationService.getEvaluation(user.id!!, evaluationId).toEvaluationResponse(userService)

    @PatchMapping("/v2/evaluations/{evaluationId}")
    fun updateEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
        @RequestBody body: EvaluationUpdateRequestBody,
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
            ).toEvaluationResponse(userService)

    @DeleteMapping("/v2/evaluations/{evaluationId}")
    fun deleteEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ) {
        evaluationService.deleteEvaluation(user.id!!, evaluationId)
    }

    @PostMapping("/v2/evaluations/{evaluationId}/report")
    fun reportEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
        @RequestBody body: EvaluationReportRequestBody,
    ): Long = evaluationService.reportEvaluation(user.id!!, evaluationId, EvaluationReportRequest(content = body.content)).id!!

    @PostMapping("/v2/evaluations/{evaluationId}/like")
    fun likeEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ) {
        evaluationService.likeEvaluation(user.id!!, evaluationId)
    }

    @DeleteMapping("/v2/evaluations/{evaluationId}/like")
    fun cancelLikeEvaluation(
        @CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ) {
        evaluationService.cancelLikeEvaluation(user.id!!, evaluationId)
    }
}
