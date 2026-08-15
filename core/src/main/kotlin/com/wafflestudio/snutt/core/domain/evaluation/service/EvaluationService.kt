package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationAverages
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSummary
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationLike
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationReport
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationLikeRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationReportRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class EvaluationWriteRequest(
    val content: String,
    val gradeSatisfaction: Double,
    val teachingSkill: Double,
    val gains: Double,
    val lifeBalance: Double,
    val rating: Double,
)

data class EvaluationUpdateRequest(
    val content: String? = null,
    val gradeSatisfaction: Double? = null,
    val teachingSkill: Double? = null,
    val gains: Double? = null,
    val lifeBalance: Double? = null,
    val rating: Double? = null,
    val moveToLectureExternalId: String? = null,
)

data class EvaluationReportRequest(
    val content: String,
)

data class EvaluationDisplay(
    val evaluation: Evaluation,
    val isLiked: Boolean,
    val isModifiable: Boolean,
    val isReportable: Boolean,
)

// course 집계 갱신은 강의평 쓰기 트랜잭션 안에서 수행한다 (구 RATING_SYNC_JOB/MongoService의 대체)
@Service
class EvaluationService(
    private val evaluationRepository: EvaluationRepository,
    private val evaluationLikeRepository: EvaluationLikeRepository,
    private val evaluationReportRepository: EvaluationReportRepository,
    private val lectureRepository: LectureRepository,
    private val courseRepository: CourseRepository,
    private val courseAggregateUpdater: CourseAggregateUpdater,
) {
    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
    }

    @Transactional
    fun createEvaluation(
        userId: Long,
        lectureExternalId: String,
        request: EvaluationWriteRequest,
    ): EvaluationDisplay {
        val (courseId, year, semester) = resolveLectureAnchor(lectureExternalId)
        validateRatings(request.gradeSatisfaction, request.teachingSkill, request.gains, request.lifeBalance, request.rating)
        if (evaluationRepository.existsByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(courseId, year, semester, userId)) {
            throw SnuttException(ErrorType.DUPLICATE_EVALUATION)
        }
        val evaluation =
            evaluationRepository.save(
                Evaluation(
                    courseId = courseId,
                    userId = userId,
                    year = year,
                    semester = semester,
                    content = request.content,
                    gradeSatisfaction = request.gradeSatisfaction,
                    teachingSkill = request.teachingSkill,
                    gains = request.gains,
                    lifeBalance = request.lifeBalance,
                    rating = request.rating,
                ),
            )
        courseAggregateUpdater.update(courseId)
        return evaluation.toDisplay(userId)
    }

    fun getEvaluationsOfLecture(
        userId: Long,
        lectureExternalId: String,
        cursor: String?,
    ): CursorPage<EvaluationDisplay> {
        val (courseId, year, semester) = resolveLectureAnchor(lectureExternalId)
        val totalCount = evaluationRepository.countByCourseIdAndYearAndSemesterAndIsHiddenFalse(courseId, year, semester)
        val page =
            evaluationRepository.findOthersByCourseAndSemester(
                courseId = courseId,
                year = year,
                semester = semester,
                userId = userId,
                cursor = CursorCodec.decode<EvaluationCursor>(cursor),
                pageSize = DEFAULT_PAGE_SIZE + 1,
            )
        return page.toCursorPage(
            DEFAULT_PAGE_SIZE,
            totalCount,
            { EvaluationCursor(it.year, it.semester.value, it.id!!) },
            { it.toDisplay(userId) },
        )
    }

    fun getMyEvaluationsOfLecture(
        userId: Long,
        lectureExternalId: String,
    ): List<EvaluationDisplay> {
        val (courseId, year, semester) = resolveLectureAnchor(lectureExternalId)
        return evaluationRepository
            .findByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(courseId, year, semester, userId)
            .map { it.toDisplay(userId) }
    }

    fun getMyEvaluations(
        userId: Long,
        cursor: String?,
    ): CursorPage<EvaluationDisplay> {
        val totalCount = evaluationRepository.countByUserIdAndIsHiddenFalse(userId)
        val cursorId = CursorCodec.decode<Long>(cursor)
        val page = evaluationRepository.findMine(userId, cursorId, DEFAULT_PAGE_SIZE + 1)
        return page.toCursorPage(DEFAULT_PAGE_SIZE, totalCount, { it.id!! }, { it.toDisplay(userId) })
    }

    fun getEvaluationsByTag(
        userId: Long,
        tag: EvaluationTag,
        cursor: String?,
    ): CursorPage<EvaluationDisplay> {
        val cursorId = CursorCodec.decode<Long>(cursor)
        val page = evaluationRepository.findByTag(tag, cursorId, DEFAULT_PAGE_SIZE + 1)
        return page.toCursorPage(DEFAULT_PAGE_SIZE, null, { it.id!! }, { it.toDisplay(userId) })
    }

    fun getEvaluation(
        userId: Long,
        evaluationId: Long,
    ): EvaluationDisplay =
        (evaluationRepository.findByIdAndIsHiddenFalse(evaluationId) ?: throw SnuttException(ErrorType.EVALUATION_NOT_FOUND))
            .toDisplay(userId)

    @Transactional
    fun updateEvaluation(
        userId: Long,
        evaluationId: Long,
        request: EvaluationUpdateRequest,
    ): EvaluationDisplay {
        val evaluation =
            evaluationRepository.findByIdAndIsHiddenFalse(evaluationId)
                ?: throw SnuttException(ErrorType.EVALUATION_NOT_FOUND)
        if (evaluation.userId != userId) throw SnuttException(ErrorType.NOT_MY_EVALUATION)
        validateRatings(request.gradeSatisfaction, request.teachingSkill, request.gains, request.lifeBalance, request.rating)

        // 내용이 실제로 바뀌면 공감을 초기화한다 (v1 동일)
        if (isUpdatingAny(evaluation, request)) {
            evaluation.likeCount = 0
            evaluationLikeRepository.deleteByEvaluationId(evaluationId)
        }
        request.content?.let {
            if (it.isBlank()) throw SnuttException(ErrorType.EVALUATION_CONTENT_BLANK)
            evaluation.content = it
        }
        request.gradeSatisfaction?.let { evaluation.gradeSatisfaction = it }
        request.teachingSkill?.let { evaluation.teachingSkill = it }
        request.gains?.let { evaluation.gains = it }
        request.lifeBalance?.let { evaluation.lifeBalance = it }
        request.rating?.let { evaluation.rating = it }
        request.moveToLectureExternalId?.let { moveTo(evaluation, it) }

        courseAggregateUpdater.update(evaluation.courseId)
        return evaluation.toDisplay(userId)
    }

    private fun moveTo(
        evaluation: Evaluation,
        lectureExternalId: String,
    ) {
        val (courseId, year, semester) = resolveLectureAnchor(lectureExternalId)
        if (courseId != evaluation.courseId) throw SnuttException(ErrorType.EVALUATION_LECTURE_MISMATCH)
        if (year == evaluation.year && semester == evaluation.semester) return
        evaluation.year = year
        evaluation.semester = semester
        conflictAs(ErrorType.DUPLICATE_EVALUATION) { evaluationRepository.flush() }
    }

    @Transactional
    fun deleteEvaluation(
        userId: Long,
        evaluationId: Long,
    ) {
        val evaluation =
            evaluationRepository.findByIdAndIsHiddenFalse(evaluationId)
                ?: throw SnuttException(ErrorType.EVALUATION_NOT_FOUND)
        if (evaluation.userId != userId) throw SnuttException(ErrorType.NOT_MY_EVALUATION)
        evaluation.isHidden = true
        courseAggregateUpdater.update(evaluation.courseId)
    }

    @Transactional
    fun reportEvaluation(
        userId: Long,
        evaluationId: Long,
        request: EvaluationReportRequest,
    ): EvaluationReport {
        if (request.content.isBlank()) throw SnuttException(ErrorType.EVALUATION_REPORT_CONTENT_BLANK)
        val evaluation =
            evaluationRepository.findByIdAndIsHiddenFalse(evaluationId)
                ?: throw SnuttException(ErrorType.EVALUATION_NOT_FOUND)
        if (evaluation.userId == userId) throw SnuttException(ErrorType.MY_EVALUATION_REPORT)
        if (evaluationReportRepository.existsByEvaluationIdAndUserId(evaluationId, userId)) {
            throw SnuttException(ErrorType.DUPLICATE_EVALUATION_REPORT)
        }
        return evaluationReportRepository
            .save(EvaluationReport(evaluationId = evaluationId, userId = userId, content = request.content))
    }

    // 강의평이 가리키는 과목. 응답 조립에 필요한 만큼만 노출한다
    fun getCourses(courseIds: Collection<Long>): Map<Long, Course> =
        courseRepository.findAllById(courseIds.distinct()).associateBy { it.id!! }

    @Transactional
    fun likeEvaluation(
        userId: Long,
        evaluationId: Long,
    ) {
        val evaluation =
            evaluationRepository.findByIdAndIsHiddenFalse(evaluationId)
                ?: throw SnuttException(ErrorType.EVALUATION_NOT_FOUND)
        conflictAs(ErrorType.DUPLICATE_EVALUATION_LIKE) {
            evaluationLikeRepository.save(EvaluationLike(evaluationId = evaluationId, userId = userId))
        }
        evaluation.likeCount++
    }

    @Transactional
    fun cancelLikeEvaluation(
        userId: Long,
        evaluationId: Long,
    ) {
        val evaluation =
            evaluationRepository.findByIdAndIsHiddenFalse(evaluationId)
                ?: throw SnuttException(ErrorType.EVALUATION_NOT_FOUND)
        val deleted = evaluationLikeRepository.deleteByEvaluationIdAndUserId(evaluationId, userId)
        if (deleted == 0) throw SnuttException(ErrorType.EVALUATION_LIKE_NOT_FOUND)
        evaluation.likeCount = (evaluation.likeCount - 1).coerceAtLeast(0)
    }

    // 검색 응답용 ev summary: lecture id → (course 집계). lecture 도메인을 건드리지 않는다
    fun findSummariesByLectureIds(lectureIds: Collection<Long>): Map<Long, EvaluationSummary> =
        evaluationRepository.findSummariesByLectureIds(lectureIds)

    data class LectureEvaluationSummaryDisplay(
        val lecture: Lecture,
        val averages: EvaluationAverages?,
    )

    fun getEvaluationSummaryOfLecture(lectureExternalId: String): LectureEvaluationSummaryDisplay {
        val lecture =
            lectureRepository.findByExternalId(lectureExternalId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val courseId = lecture.courseId ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND)
        return LectureEvaluationSummaryDisplay(
            lecture = lecture,
            averages = evaluationRepository.findEvaluationAverages(courseId, lecture.year, lecture.semester),
        )
    }

    private data class LectureAnchor(
        val courseId: Long,
        val year: Int,
        val semester: Semester,
    )

    private fun resolveLectureAnchor(lectureExternalId: String): LectureAnchor {
        val lecture =
            lectureRepository.findByExternalId(lectureExternalId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val courseId = lecture.courseId ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND)
        return LectureAnchor(courseId, lecture.year, lecture.semester)
    }

    // EV의 @Range(min = 1, max = 5) 이식
    private fun validateRatings(vararg ratings: Double?) {
        if (ratings.filterNotNull().any { it < 1.0 || it > 5.0 }) {
            throw SnuttException(ErrorType.EVALUATION_RATING_OUT_OF_RANGE)
        }
    }

    private fun isUpdatingAny(
        evaluation: Evaluation,
        request: EvaluationUpdateRequest,
    ): Boolean =
        (request.content != null && request.content != evaluation.content) ||
            (request.gradeSatisfaction != null && request.gradeSatisfaction != evaluation.gradeSatisfaction) ||
            (request.teachingSkill != null && request.teachingSkill != evaluation.teachingSkill) ||
            (request.gains != null && request.gains != evaluation.gains) ||
            (request.lifeBalance != null && request.lifeBalance != evaluation.lifeBalance) ||
            (request.rating != null && request.rating != evaluation.rating)

    private fun Evaluation.toDisplay(userId: Long) =
        EvaluationDisplay(
            evaluation = this,
            isLiked = evaluationLikeRepository.existsByEvaluationIdAndUserId(id!!, userId),
            isModifiable = this.userId == userId,
            isReportable = this.userId != userId,
        )

    private fun <T> List<T>.toCursorPage(
        pageSize: Int,
        totalCount: Long?,
        cursorOf: (T) -> Any,
        mapper: (T) -> EvaluationDisplay,
    ): CursorPage<EvaluationDisplay> {
        val hasMore = size > pageSize
        val page = if (hasMore) dropLast(1) else this
        val nextCursor = if (hasMore) page.lastOrNull()?.let { CursorCodec.encode(cursorOf(it)) } else null
        return CursorPage.of(page.map(mapper), nextCursor, pageSize, totalCount)
    }
}
