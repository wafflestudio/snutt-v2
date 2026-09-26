package com.wafflestudio.snutt.v1compat.ev

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSort
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationReportRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationUpdateRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.user.model.User
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
    @param:JsonProperty("grade_satisfaction")
    val gradeSatisfaction: Double,
    @param:JsonProperty("teaching_skill")
    val teachingSkill: Double,
    val gains: Double,
    @param:JsonProperty("life_balance")
    val lifeBalance: Double,
    val rating: Double,
)

data class LegacyEvaluationUpdateRequest(
    val content: String? = null,
    @param:JsonProperty("grade_satisfaction")
    val gradeSatisfaction: Double? = null,
    @param:JsonProperty("teaching_skill")
    val teachingSkill: Double? = null,
    val gains: Double? = null,
    @param:JsonProperty("life_balance")
    val lifeBalance: Double? = null,
    val rating: Double? = null,
    @param:JsonProperty("semester_lecture_id")
    val semesterLectureId: String? = null,
)

data class LegacyEvaluationReportRequest(
    val content: String,
)

data class LegacyMyLectureEvaluationsResponse(
    val evaluations: List<LegacyEvaluationWithSemesterDto>,
)

data class LegacyEvLectureSummaryResponse(
    val id: Long?,
    val title: String,
    val instructor: String?,
    val department: String?,
    @param:JsonProperty("course_number")
    val courseNumber: String,
    val credit: Int?,
    @param:JsonProperty("academic_year")
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val evaluation: LegacyEvAveragesDto,
)

data class LegacyEvAveragesDto(
    @param:JsonProperty("avg_grade_satisfaction")
    val avgGradeSatisfaction: Double?,
    @param:JsonProperty("avg_teaching_skill")
    val avgTeachingSkill: Double?,
    @param:JsonProperty("avg_gains")
    val avgGains: Double?,
    @param:JsonProperty("avg_life_balance")
    val avgLifeBalance: Double?,
    @param:JsonProperty("avg_rating")
    val avgRating: Double?,
    @param:JsonProperty("evaluation_count")
    val evaluationCount: Long,
)

data class LegacyEvaluationReportResponse(
    val id: Long?,
    @param:JsonProperty("lecture_evaluation_id")
    val lectureEvaluationId: Long,
    @param:JsonProperty("user_id")
    val userId: String?,
    val content: String,
    @param:JsonProperty("is_hidden")
    val isHidden: Boolean,
)

@RestController
@V1EmailVerifiedRequired
@RequestMapping(V1_EV_SERVICE_PATH, V1_EV_PATH, EV_SERVICE_PATH, EV_PATH)
class V1CompatEvController(
    private val evaluationService: EvaluationService,
    private val legacyCourseRepository: LegacyCourseRepository,
) {
    @GetMapping("/lectures/{lectureId}/evaluations")
    fun getEvaluationsOfLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: Long,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) semester: Semester?,
    ): LegacyEvCursorPage<LegacyEvaluationWithSemesterDto> {
        val page =
            evaluationService.getEvaluationsOfCourse(
                userId = user.id!!,
                courseId = lectureId,
                cursor = cursor,
                sort = EvaluationSort.fromParameter(sort),
                year = year,
                semester = semester,
            )
        return page.toLegacyEvPage { it.toLegacyWithSemester() }
    }

    @PostMapping("/semester-lectures/{semesterLectureId}/evaluations")
    fun createEvaluation(
        @V1CurrentUser user: User,
        @PathVariable semesterLectureId: Long,
        @RequestBody body: LegacyEvaluationWriteRequest,
    ): LegacyEvaluationCreateResponse =
        evaluationService
            .createEvaluation(
                user.id!!,
                semesterLectureId,
                EvaluationWriteRequest(
                    content = body.content,
                    gradeSatisfaction = body.gradeSatisfaction,
                    teachingSkill = body.teachingSkill,
                    gains = body.gains,
                    lifeBalance = body.lifeBalance,
                    rating = body.rating,
                ),
            ).toLegacyCreate()

    @GetMapping("/lectures/{lectureId}/evaluations/users/me")
    fun getMyEvaluationsOfLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: Long,
    ): LegacyMyLectureEvaluationsResponse {
        val evaluations = evaluationService.getMyEvaluationsOfCourse(user.id!!, lectureId)
        return LegacyMyLectureEvaluationsResponse(evaluations = evaluations.map { it.toLegacyWithSemester() })
    }

    @GetMapping("/lectures/{lectureId}/evaluation-summary")
    fun getEvaluationSummaryOfLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: Long,
    ): LegacyEvLectureSummaryResponse {
        val summary = evaluationService.getEvaluationSummaryOfCourse(lectureId)
        val course = legacyCourseRepository.get(lectureId)
        val averages = summary.aggregate.averages
        return LegacyEvLectureSummaryResponse(
            id = course.id,
            title = course.title,
            instructor = course.instructor,
            department = course.department,
            courseNumber = course.courseNumber,
            credit = course.credit,
            academicYear = course.academicYear,
            category = course.category,
            classification = course.classification,
            evaluation =
                LegacyEvAveragesDto(
                    avgGradeSatisfaction = averages.avgGradeSatisfaction,
                    avgTeachingSkill = averages.avgTeachingSkill,
                    avgGains = averages.avgGains,
                    avgLifeBalance = averages.avgLifeBalance,
                    avgRating = averages.avgRating,
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
        val courseMap = courseMap(page.content.map { it.evaluation.courseId })
        return page.toLegacyEvPage { it.toLegacyWithLecture(courseMap) }
    }

    @GetMapping("/evaluations/{evaluationId}")
    fun getEvaluation(
        @V1CurrentUser user: User,
        @PathVariable evaluationId: Long,
    ): LegacyEvaluationWithSemesterDto {
        val display = evaluationService.getEvaluation(user.id!!, evaluationId)
        return display.toLegacyWithSemester()
    }

    @PatchMapping("/evaluations/{evaluationId}")
    fun updateEvaluation(
        @V1CurrentUser user: User,
        @PathVariable evaluationId: Long,
        @RequestBody body: LegacyEvaluationUpdateRequest,
    ): LegacyEvaluationWithSemesterDto {
        val request =
            EvaluationUpdateRequest(
                moveToLectureId = body.semesterLectureId?.let { it.toLongOrNull() ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND) },
                content = body.content,
                gradeSatisfaction = body.gradeSatisfaction,
                teachingSkill = body.teachingSkill,
                gains = body.gains,
                lifeBalance = body.lifeBalance,
                rating = body.rating,
            )
        val display = evaluationService.updateEvaluation(user.id!!, evaluationId, request)
        return display.toLegacyWithSemester()
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
        val courseMap = courseMap(page.content.map { it.evaluation.courseId })
        return page.toLegacyEvPage { it.toLegacyWithLecture(courseMap) }
    }

    private fun courseMap(courseIds: Collection<Long>): Map<Long, LegacyCourseMetadata> = legacyCourseRepository.getByIds(courseIds)
}
