package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSort
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationReportRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationUpdateRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
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
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvaluationWriteRequest(
    val content: String,
    val gradeSatisfaction: Double,
    val teachingSkill: Double,
    val gains: Double,
    val lifeBalance: Double,
    val rating: Double,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvaluationUpdateRequest(
    val content: String? = null,
    val gradeSatisfaction: Double? = null,
    val teachingSkill: Double? = null,
    val gains: Double? = null,
    val lifeBalance: Double? = null,
    val rating: Double? = null,
    val semesterLectureId: String? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvaluationReportRequest(
    val content: String,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyMyLectureEvaluationsResponse(
    val evaluations: List<LegacyEvaluationWithSemesterDto>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvLectureSummaryResponse(
    val id: Long?,
    val title: String,
    val instructor: String?,
    val department: String?,
    val courseNumber: String,
    val credit: Int,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val evaluation: LegacyEvAveragesDto,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvAveragesDto(
    val avgGradeSatisfaction: Double?,
    val avgTeachingSkill: Double?,
    val avgGains: Double?,
    val avgLifeBalance: Double?,
    val avgRating: Double?,
    val evaluationCount: Long,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvaluationReportResponse(
    val id: Long?,
    val lectureEvaluationId: Long,
    val userId: String?,
    val content: String,
    val isHidden: Boolean,
)

@RestController
@V1EmailVerifiedRequired
@RequestMapping("/v1/ev-service/v1", "/v1/ev/v1")
class V1CompatEvController(
    private val evaluationService: EvaluationService,
    private val userService: UserService,
    private val lectureService: LectureService,
) {
    @GetMapping("/lectures/{lectureId}/evaluations")
    fun getEvaluationsOfLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: Long,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) semester: Int?,
    ): LegacyEvCursorPage<LegacyEvaluationWithSemesterDto> {
        val page =
            evaluationService.getEvaluationsOfCourse(
                userId = user.id!!,
                courseId = lectureId,
                cursor = cursor,
                sort = EvaluationSort.fromParameter(sort),
                year = year,
                semester = semester?.let { Semester.getOfValue(it) ?: throw SnuttException(ErrorType.INVALID_PARAMETER) },
            )
        val userExternalIds = userExternalIds(page.content.mapNotNull { it.evaluation.userId })
        return LegacyEvCursorPage(
            content = page.content.map { it.toLegacyWithSemester(userExternalIds) },
            cursor = page.cursor,
            size = page.size,
            last = page.last,
            totalCount = page.totalCount,
        )
    }

    @PostMapping("/semester-lectures/{semesterLectureId}/evaluations")
    fun createEvaluation(
        @V1CurrentUser user: User,
        @PathVariable semesterLectureId: Long,
        @RequestBody body: LegacyEvaluationWriteRequest,
    ): LegacyEvaluationCreateResponse {
        val lecture = lectureService.get(semesterLectureId)
        val courseId = lecture.courseId ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND)
        return evaluationService
            .createEvaluation(
                user.id!!,
                courseId,
                lecture.year,
                lecture.semester,
                EvaluationWriteRequest(
                    content = body.content,
                    gradeSatisfaction = body.gradeSatisfaction,
                    teachingSkill = body.teachingSkill,
                    gains = body.gains,
                    lifeBalance = body.lifeBalance,
                    rating = body.rating,
                ),
            ).toLegacyCreate(userExternalIds(listOf(user.id!!)))
    }

    @GetMapping("/lectures/{lectureId}/evaluations/users/me")
    fun getMyEvaluationsOfLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: Long,
    ): LegacyMyLectureEvaluationsResponse {
        val evaluations = evaluationService.getMyEvaluationsOfCourse(user.id!!, lectureId)
        val userExternalIds = userExternalIds(evaluations.mapNotNull { it.evaluation.userId })
        return LegacyMyLectureEvaluationsResponse(evaluations = evaluations.map { it.toLegacyWithSemester(userExternalIds) })
    }

    @GetMapping("/lectures/{lectureId}/evaluation-summary")
    fun getEvaluationSummaryOfLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: Long,
    ): LegacyEvLectureSummaryResponse {
        val display = evaluationService.getEvaluationSummaryOfCourse(lectureId)
        val course = display.course
        val averages = display.averages
        return LegacyEvLectureSummaryResponse(
            id = course.id!!,
            title = course.title,
            instructor = course.instructor,
            department = course.department,
            courseNumber = course.courseNumber,
            credit = course.credit ?: 0,
            academicYear = course.academicYear,
            category = course.category,
            classification = course.classification,
            evaluation =
                LegacyEvAveragesDto(
                    avgGradeSatisfaction = averages?.avgGradeSatisfaction,
                    avgTeachingSkill = averages?.avgTeachingSkill,
                    avgGains = averages?.avgGains,
                    avgLifeBalance = averages?.avgLifeBalance,
                    avgRating = averages?.avgRating,
                    evaluationCount = course.evalCount,
                ),
        )
    }

    @GetMapping("/evaluations/me", "/evaluations/users/me")
    fun getMyEvaluations(
        @V1CurrentUser user: User,
        @RequestParam(required = false) cursor: String?,
    ): LegacyEvCursorPage<LegacyEvaluationWithLectureDto> {
        val page = evaluationService.getMyEvaluations(user.id!!, cursor)
        val userExternalIds = userExternalIds(page.content.mapNotNull { it.evaluation.userId })
        val courseMap = courseMap(page.content.map { it.evaluation.courseId })
        return LegacyEvCursorPage(
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
    ): LegacyEvaluationWithSemesterDto {
        val display = evaluationService.getEvaluation(user.id!!, evaluationId)
        return display.toLegacyWithSemester(userExternalIds(listOfNotNull(display.evaluation.userId)))
    }

    @PatchMapping("/evaluations/{evaluationId}")
    fun updateEvaluation(
        @V1CurrentUser user: User,
        @PathVariable evaluationId: Long,
        @RequestBody body: LegacyEvaluationUpdateRequest,
    ): LegacyEvaluationWithSemesterDto {
        val request =
            EvaluationUpdateRequest(
                content = body.content,
                gradeSatisfaction = body.gradeSatisfaction,
                teachingSkill = body.teachingSkill,
                gains = body.gains,
                lifeBalance = body.lifeBalance,
                rating = body.rating,
            )
        val semesterLectureId = body.semesterLectureId?.toLongOrNull()
        val display =
            if (body.semesterLectureId == null) {
                evaluationService.updateEvaluation(user.id!!, evaluationId, request)
            } else {
                val lecture =
                    semesterLectureId?.let(lectureService::get)
                        ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND)
                val courseId = lecture.courseId ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND)
                evaluationService.updateEvaluationForCourseSemester(
                    user.id!!,
                    evaluationId,
                    request,
                    courseId,
                    lecture.year,
                    lecture.semester,
                )
            }
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
    ): LegacyEvaluationReportResponse {
        val report = evaluationService.reportEvaluation(user.id!!, evaluationId, EvaluationReportRequest(content = body.content))
        return LegacyEvaluationReportResponse(
            id = report.id,
            lectureEvaluationId = report.evaluationId,
            userId = report.userId.toString(),
            content = report.content,
            isHidden = report.isHidden,
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
    ): LegacyEvTagGroupDto = legacyMainTagGroup()

    @GetMapping("/tags/main/{tagId}/evaluations")
    fun getMainTagEvaluations(
        @V1CurrentUser user: User,
        @PathVariable tagId: Long,
        @RequestParam(required = false) cursor: String?,
    ): LegacyEvCursorPage<LegacyEvaluationWithLectureDto> {
        val tag = evaluationTagOfLegacyId(tagId) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        val page = evaluationService.getEvaluationsByTag(user.id!!, tag, cursor)
        val userExternalIds = userExternalIds(page.content.mapNotNull { it.evaluation.userId })
        val courseMap = courseMap(page.content.map { it.evaluation.courseId })
        return LegacyEvCursorPage(
            content = page.content.map { it.toLegacyWithLecture(userExternalIds, courseMap) },
            cursor = page.cursor,
            size = page.size,
            last = page.last,
            totalCount = page.totalCount,
        )
    }

    private fun userExternalIds(userIds: Collection<Long>): Map<Long, String> = userIds.associateWith { it.toString() }

    private fun courseMap(courseIds: Collection<Long>): Map<Long, Course> = evaluationService.getCourses(courseIds)
}
