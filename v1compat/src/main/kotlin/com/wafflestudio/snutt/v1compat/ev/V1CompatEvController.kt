package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationReportRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationUpdateRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.UserService
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1EmailVerifiedRequired
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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
    val semesterLectureId: String? = null,
)

data class LegacyEvaluationReportRequest(
    val content: String,
)

@RestController
@V1EmailVerifiedRequired
@RequestMapping("/v1/ev-service/v1", "/v1/ev/v1")
class V1CompatEvController(
    private val evaluationService: EvaluationService,
    private val userService: UserService,
) {
    @GetMapping("/lectures/{lectureId}/evaluations")
    fun getEvaluationsOfLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: String,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<Map<String, Any?>> {
        val page = evaluationService.getEvaluationsOfLecture(user.id!!, lectureId, cursor)
        val userExternalIds = userExternalIds(page.content.mapNotNull { it.evaluation.userId })
        return CursorPage(
            content = page.content.map { it.toLegacyWithSemester(userExternalIds) },
            cursor = page.cursor,
            size = page.size,
            last = page.last,
            totalCount = page.totalCount,
        )
    }

    @PostMapping("/semester-lectures/{lectureId}/evaluations")
    fun createEvaluation(
        @V1CurrentUser user: User,
        @PathVariable lectureId: String,
        @RequestBody body: LegacyEvaluationWriteRequest,
    ): Map<String, Any?> =
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
            ).toLegacyCreate(userExternalIds(listOf(user.id!!)))

    @GetMapping("/lectures/{lectureId}/evaluations/users/me")
    fun getMyEvaluationsOfLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: String,
    ): Map<String, Any?> {
        val evaluations = evaluationService.getMyEvaluationsOfLecture(user.id!!, lectureId)
        val userExternalIds = userExternalIds(evaluations.mapNotNull { it.evaluation.userId })
        return mapOf("evaluations" to evaluations.map { it.toLegacyWithSemester(userExternalIds) })
    }

    @GetMapping("/lectures/{lectureId}/evaluation-summary")
    fun getEvaluationSummaryOfLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: String,
    ): Map<String, Any?> {
        val display = evaluationService.getEvaluationSummaryOfLecture(lectureId)
        val lecture = display.lecture
        val averages = display.averages
        return linkedMapOf(
            "id" to lecture.courseId,
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

    @GetMapping("/evaluations/me", "/evaluations/users/me")
    fun getMyEvaluations(
        @V1CurrentUser user: User,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<Map<String, Any?>> {
        val page = evaluationService.getMyEvaluations(user.id!!, cursor)
        val userExternalIds = userExternalIds(page.content.mapNotNull { it.evaluation.userId })
        val courseMap = courseMap(page.content.map { it.evaluation.courseId })
        return CursorPage(
            content = page.content.map { it.toLegacyWithLecture(userExternalIds, courseMap) },
            cursor = page.cursor,
            size = page.size,
            last = page.last,
            totalCount = page.totalCount,
        )
    }

    @GetMapping("/evaluations/{evaluationId}")
    fun getEvaluation(
        @V1CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ): Map<String, Any?> {
        val display = evaluationService.getEvaluation(user.id!!, evaluationId)
        return display.toLegacyWithSemester(userExternalIds(listOfNotNull(display.evaluation.userId)))
    }

    @PatchMapping("/evaluations/{evaluationId}")
    fun updateEvaluation(
        @V1CurrentUser user: User,
        @PathVariable evaluationId: Long,
        @RequestBody body: LegacyEvaluationUpdateRequest,
    ): Map<String, Any?> {
        val display =
            evaluationService.updateEvaluation(
                user.id!!,
                evaluationId,
                EvaluationUpdateRequest(
                    content = body.content,
                    gradeSatisfaction = body.gradeSatisfaction,
                    teachingSkill = body.teachingSkill,
                    gains = body.gains,
                    lifeBalance = body.lifeBalance,
                    rating = body.rating,
                    moveToLectureExternalId = body.semesterLectureId,
                ),
            )
        return display.toLegacyWithSemester(userExternalIds(listOfNotNull(display.evaluation.userId)))
    }

    @DeleteMapping("/evaluations/{evaluationId}")
    fun deleteEvaluation(
        @V1CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ) {
        evaluationService.deleteEvaluation(user.id!!, evaluationId)
    }

    @PostMapping("/evaluations/{evaluationId}/report")
    fun reportEvaluation(
        @V1CurrentUser user: User,
        @PathVariable evaluationId: Long,
        @RequestBody body: LegacyEvaluationReportRequest,
    ): Map<String, Any?> {
        val report = evaluationService.reportEvaluation(user.id!!, evaluationId, EvaluationReportRequest(content = body.content))
        return linkedMapOf(
            "id" to report.id,
            "lectureEvaluationId" to report.evaluationId,
            "userId" to userService.getExternalIds(listOf(report.userId))[report.userId],
            "content" to report.content,
            "isHidden" to report.isHidden,
        )
    }

    @PostMapping("/evaluations/{evaluationId}/likes")
    fun likeEvaluation(
        @V1CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ) {
        evaluationService.likeEvaluation(user.id!!, evaluationId)
    }

    @DeleteMapping("/evaluations/{evaluationId}/likes")
    fun cancelLikeEvaluation(
        @V1CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ) {
        evaluationService.cancelLikeEvaluation(user.id!!, evaluationId)
    }

    @GetMapping("/tags/main")
    fun getMainTags(
        @V1CurrentUser user: User,
    ): Map<String, Any?> = legacyMainTagGroup()

    @GetMapping("/tags/main/{tagId}/evaluations")
    fun getMainTagEvaluations(
        @V1CurrentUser user: User,
        @PathVariable tagId: Long,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<Map<String, Any?>> {
        val tag = evaluationTagOfLegacyId(tagId) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        val page = evaluationService.getEvaluationsByTag(user.id!!, tag, cursor)
        val userExternalIds = userExternalIds(page.content.mapNotNull { it.evaluation.userId })
        val courseMap = courseMap(page.content.map { it.evaluation.courseId })
        return CursorPage(
            content = page.content.map { it.toLegacyWithLecture(userExternalIds, courseMap) },
            cursor = page.cursor,
            size = page.size,
            last = page.last,
            totalCount = page.totalCount,
        )
    }

    private fun userExternalIds(userIds: Collection<Long>): Map<Long, String> = userService.getExternalIds(userIds)

    private fun courseMap(courseIds: Collection<Long>): Map<Long, Course> = evaluationService.getCourses(courseIds)
}
