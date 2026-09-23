package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseAggregate
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationIdCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSort
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
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
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
    val moveToLectureId: Long? = null,
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

data class CourseEvaluationSummary(
    val course: Course,
    val aggregate: CourseAggregate,
)

@Service
class EvaluationService(
    private val evaluationRepository: EvaluationRepository,
    private val evaluationLikeRepository: EvaluationLikeRepository,
    private val evaluationReportRepository: EvaluationReportRepository,
    private val lectureRepository: LectureRepository,
    private val courseRepository: CourseRepository,
    private val courseAggregateUpdater: CourseAggregateUpdater,
    private val cursorCodec: CursorCodec,
) {
    companion object {
        private const val PAGE_SIZE = 20
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createEvaluation(
        userId: Long,
        lectureId: Long,
        request: EvaluationWriteRequest,
    ): EvaluationDisplay {
        if (request.content.isBlank()) throw SnuttException(ErrorType.EVALUATION_CONTENT_BLANK)
        val lecture = getLecture(lectureId)
        val courseId = lecture.courseId ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND)
        courseRepository.findForUpdateById(courseId) ?: throw SnuttException(ErrorType.COURSE_NOT_FOUND)
        validateRatings(request.gradeSatisfaction, request.teachingSkill, request.gains, request.lifeBalance, request.rating)
        ensureNotEvaluated(courseId, lecture.year, lecture.semester, userId)
        val evaluation =
            conflictAs(ErrorType.DUPLICATE_EVALUATION) {
                evaluationRepository.save(
                    Evaluation(
                        courseId = courseId,
                        userId = userId,
                        year = lecture.year,
                        semester = lecture.semester,
                        content = request.content,
                        gradeSatisfaction = request.gradeSatisfaction,
                        teachingSkill = request.teachingSkill,
                        gains = request.gains,
                        lifeBalance = request.lifeBalance,
                        rating = request.rating,
                    ),
                )
            }
        courseAggregateUpdater.update(courseId)
        return evaluation.toDisplay(userId)
    }

    fun getEvaluationsOfCourse(
        userId: Long,
        courseId: Long,
        cursor: String?,
        sort: EvaluationSort = EvaluationSort.LATEST,
        year: Int? = null,
        semester: Semester? = null,
    ): CursorPage<EvaluationDisplay> {
        courseRepository.findByIdOrNull(courseId) ?: throw SnuttException(ErrorType.COURSE_NOT_FOUND)
        val totalCount = evaluationRepository.countOthers(courseId, userId, year, semester)
        val page =
            evaluationRepository.findOthers(
                courseId = courseId,
                userId = userId,
                year = year,
                semester = semester,
                cursor = decodeEvaluationCursor(cursor, sort),
                pageSize = PAGE_SIZE + 1,
                sort = sort,
            )
        return cursorCodec.pageOf(page, PAGE_SIZE, totalCount, { it.toCursor(sort) }) { it.toDisplays(userId) }
    }

    fun getMyEvaluationsOfCourse(
        userId: Long,
        courseId: Long,
    ): List<EvaluationDisplay> {
        courseRepository.findByIdOrNull(courseId) ?: throw SnuttException(ErrorType.COURSE_NOT_FOUND)
        return evaluationRepository
            .findByCourseIdAndUserIdAndIsHiddenFalseOrderByYearDescSemesterDescIdDesc(courseId, userId)
            .toDisplays(userId)
    }

    fun getMyEvaluations(
        userId: Long,
        cursor: String?,
    ): CursorPage<EvaluationDisplay> {
        val totalCount = evaluationRepository.countByUserIdAndIsHiddenFalse(userId)
        val page = evaluationRepository.findMine(userId, decodeEvaluationIdCursor(cursor), PAGE_SIZE + 1)
        return cursorCodec.pageOf(page, PAGE_SIZE, totalCount, { EvaluationIdCursor(it.id!!) }) { it.toDisplays(userId) }
    }

    fun getEvaluationsByTag(
        userId: Long,
        tag: EvaluationTag,
        cursor: String?,
    ): CursorPage<EvaluationDisplay> {
        val page = evaluationRepository.findByTag(tag, decodeEvaluationIdCursor(cursor), PAGE_SIZE + 1)
        return cursorCodec.pageOf(page, PAGE_SIZE, null, { EvaluationIdCursor(it.id!!) }) { it.toDisplays(userId) }
    }

    fun getEvaluation(
        userId: Long,
        evaluationId: Long,
    ): EvaluationDisplay =
        (evaluationRepository.findByIdAndIsHiddenFalse(evaluationId) ?: throw SnuttException(ErrorType.EVALUATION_NOT_FOUND))
            .toDisplay(userId)

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun updateEvaluation(
        userId: Long,
        evaluationId: Long,
        request: EvaluationUpdateRequest,
    ): EvaluationDisplay {
        val moveTo = request.moveToLectureId?.let(::getLecture)
        val evaluation = lockMyEvaluation(userId, evaluationId)
        courseRepository.findForUpdateById(evaluation.courseId) ?: throw SnuttException(ErrorType.COURSE_NOT_FOUND)
        validateRatings(request.gradeSatisfaction, request.teachingSkill, request.gains, request.lifeBalance, request.rating)
        if (request.content?.isBlank() == true) throw SnuttException(ErrorType.EVALUATION_CONTENT_BLANK)
        if (moveTo != null && moveTo.courseId != evaluation.courseId) throw SnuttException(ErrorType.EVALUATION_LECTURE_MISMATCH)

        val changed = evaluation.apply(request, moveTo)
        if (changed) {
            evaluation.likeCount = 0
            evaluationLikeRepository.deleteByEvaluationId(evaluationId)
        }
        if (moveTo != null && (moveTo.year != evaluation.year || moveTo.semester != evaluation.semester)) {
            ensureNotEvaluated(evaluation.courseId, moveTo.year, moveTo.semester, userId)
            evaluation.year = moveTo.year
            evaluation.semester = moveTo.semester
            conflictAs(ErrorType.DUPLICATE_EVALUATION) { evaluationRepository.flush() }
        }
        courseAggregateUpdater.update(evaluation.courseId)
        return evaluation.toDisplay(userId)
    }

    private fun Evaluation.apply(
        request: EvaluationUpdateRequest,
        moveTo: Lecture?,
    ): Boolean {
        val before = contentSnapshot()
        request.content?.let { content = it }
        request.gradeSatisfaction?.let { gradeSatisfaction = it }
        request.teachingSkill?.let { teachingSkill = it }
        request.gains?.let { gains = it }
        request.lifeBalance?.let { lifeBalance = it }
        request.rating?.let { rating = it }
        val moved = moveTo != null && (moveTo.year != year || moveTo.semester != semester)
        return moved || before != contentSnapshot()
    }

    private fun Evaluation.contentSnapshot() = listOf(content, gradeSatisfaction, teachingSkill, gains, lifeBalance, rating)

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun deleteEvaluation(
        userId: Long,
        evaluationId: Long,
    ) {
        val evaluation = lockMyEvaluation(userId, evaluationId)
        evaluation.isHidden = true
        courseAggregateUpdater.update(evaluation.courseId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun reportEvaluation(
        userId: Long,
        evaluationId: Long,
        request: EvaluationReportRequest,
    ): EvaluationReport {
        if (request.content.isBlank()) throw SnuttException(ErrorType.EVALUATION_REPORT_CONTENT_BLANK)
        val evaluation = lockEvaluation(evaluationId)
        if (evaluation.userId == userId) throw SnuttException(ErrorType.MY_EVALUATION_REPORT)
        if (evaluationReportRepository.existsByEvaluationIdAndUserId(evaluationId, userId)) {
            throw SnuttException(ErrorType.DUPLICATE_EVALUATION_REPORT)
        }
        return evaluationReportRepository.save(EvaluationReport(evaluationId = evaluationId, userId = userId, content = request.content))
    }

    fun getCourses(courseIds: Collection<Long>): Map<Long, Course> =
        courseRepository.findAllById(courseIds.distinct()).associateBy { it.id!! }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun likeEvaluation(
        userId: Long,
        evaluationId: Long,
    ) {
        lockEvaluation(evaluationId)
        conflictAs(ErrorType.DUPLICATE_EVALUATION_LIKE) {
            evaluationLikeRepository.save(EvaluationLike(evaluationId = evaluationId, userId = userId))
        }
        evaluationRepository.incrementLikeCount(evaluationId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun cancelLikeEvaluation(
        userId: Long,
        evaluationId: Long,
    ) {
        lockEvaluation(evaluationId)
        val deleted = evaluationLikeRepository.deleteByEvaluationIdAndUserId(evaluationId, userId)
        if (deleted == 0) throw SnuttException(ErrorType.EVALUATION_LIKE_NOT_FOUND)
        evaluationRepository.decrementLikeCount(evaluationId)
    }

    fun findSummariesByLectureIds(lectureIds: Collection<Long>): Map<Long, EvaluationSummary> =
        evaluationRepository.findSummariesByLectureIds(lectureIds)

    fun getEvaluationSummaryOfCourse(courseId: Long): CourseEvaluationSummary {
        val course = courseRepository.findByIdOrNull(courseId) ?: throw SnuttException(ErrorType.COURSE_NOT_FOUND)
        return CourseEvaluationSummary(course, evaluationRepository.findCourseAggregate(courseId))
    }

    private fun getLecture(lectureId: Long): Lecture =
        lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)

    private fun lockEvaluation(evaluationId: Long): Evaluation =
        evaluationRepository.findForUpdateByIdAndIsHiddenFalse(evaluationId) ?: throw SnuttException(ErrorType.EVALUATION_NOT_FOUND)

    private fun lockMyEvaluation(
        userId: Long,
        evaluationId: Long,
    ): Evaluation =
        lockEvaluation(evaluationId).also {
            if (it.userId != userId) throw SnuttException(ErrorType.NOT_MY_EVALUATION)
        }

    private fun ensureNotEvaluated(
        courseId: Long,
        year: Int,
        semester: Semester,
        userId: Long,
    ) {
        if (evaluationRepository.existsByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(courseId, year, semester, userId)) {
            throw SnuttException(ErrorType.DUPLICATE_EVALUATION)
        }
    }

    private fun validateRatings(vararg ratings: Double?) {
        if (ratings.filterNotNull().any { it < 1.0 || it > 5.0 }) {
            throw SnuttException(ErrorType.EVALUATION_RATING_OUT_OF_RANGE)
        }
    }

    private fun decodeEvaluationCursor(
        cursor: String?,
        sort: EvaluationSort,
    ): EvaluationCursor? =
        cursorCodec.decode<EvaluationCursor>(cursor)?.also {
            val validSortKey =
                when (sort) {
                    EvaluationSort.LATEST -> it.year > 0
                    EvaluationSort.RECOMMENDED -> it.likeCount != null && it.likeCount >= 0
                }
            if (it.sort != sort || it.evaluationId <= 0 || !validSortKey) throw SnuttException(ErrorType.INVALID_CURSOR)
        }

    private fun decodeEvaluationIdCursor(cursor: String?): Long? =
        cursorCodec.decode<EvaluationIdCursor>(cursor)?.let {
            if (it.evaluationId <= 0) throw SnuttException(ErrorType.INVALID_CURSOR)
            it.evaluationId
        }

    private fun Collection<Evaluation>.toDisplays(userId: Long): List<EvaluationDisplay> {
        if (isEmpty()) return emptyList()
        val likedEvaluationIds =
            evaluationLikeRepository
                .findByUserIdAndEvaluationIdIn(userId, map { it.id!! })
                .map { it.evaluationId }
                .toSet()
        return map { it.toDisplay(userId, it.id in likedEvaluationIds) }
    }

    private fun Evaluation.toCursor(sort: EvaluationSort): EvaluationCursor =
        EvaluationCursor(
            sort = sort,
            year = year,
            semester = semester,
            evaluationId = id!!,
            likeCount = likeCount.takeIf { sort == EvaluationSort.RECOMMENDED },
        )

    private fun Evaluation.toDisplay(
        userId: Long,
        isLiked: Boolean = evaluationLikeRepository.existsByEvaluationIdAndUserId(id!!, userId),
    ) = EvaluationDisplay(
        evaluation = this,
        isLiked = isLiked,
        isModifiable = this.userId == userId,
        isReportable = this.userId != userId,
    )
}
