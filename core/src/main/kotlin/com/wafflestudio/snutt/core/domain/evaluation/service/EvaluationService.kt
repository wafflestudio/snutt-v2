package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationAverages
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
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
        lectureId: Long,
        request: EvaluationWriteRequest,
    ): EvaluationDisplay {
        val lecture = findLecture(lectureId)
        return createEvaluation(
            userId,
            lecture.courseId ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND),
            lecture.year,
            lecture.semester,
            request,
        )
    }

    @Transactional
    fun createEvaluation(
        userId: Long,
        courseId: Long,
        year: Int,
        semester: Semester,
        request: EvaluationWriteRequest,
    ): EvaluationDisplay {
        if (!courseRepository.existsById(courseId)) throw SnuttException(ErrorType.COURSE_NOT_FOUND)
        validateRatings(request.gradeSatisfaction, request.teachingSkill, request.gains, request.lifeBalance, request.rating)
        if (evaluationRepository.existsByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(courseId, year, semester, userId)) {
            throw SnuttException(ErrorType.DUPLICATE_EVALUATION)
        }
        val evaluation =
            conflictAs(ErrorType.DUPLICATE_EVALUATION) {
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
            }
        courseAggregateUpdater.update(courseId)
        return evaluation.toDisplay(userId)
    }

    fun getEvaluationsOfLecture(
        userId: Long,
        lectureId: Long,
        cursor: String?,
        sort: EvaluationSort = EvaluationSort.LATEST,
        year: Int? = null,
        semester: Semester? = null,
    ): CursorPage<EvaluationDisplay> {
        val courseId = findLecture(lectureId).courseId ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND)
        val totalCount = evaluationRepository.countOthersByCourseIdAndIsHiddenFalse(courseId, userId, year, semester)
        return getEvaluationPage(userId, courseId, cursor, sort, year, semester, totalCount)
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
        val totalCount = evaluationRepository.countByCourseIdAndIsHiddenFalse(courseId, year, semester)
        return getEvaluationPage(userId, courseId, cursor, sort, year, semester, totalCount)
    }

    private fun getEvaluationPage(
        userId: Long,
        courseId: Long,
        cursor: String?,
        sort: EvaluationSort,
        year: Int?,
        semester: Semester?,
        totalCount: Long,
    ): CursorPage<EvaluationDisplay> {
        val page =
            evaluationRepository.findOthersByCourseAndSemester(
                courseId = courseId,
                year = year,
                semester = semester,
                userId = userId,
                cursor = CursorCodec.decode<EvaluationCursor>(cursor),
                pageSize = DEFAULT_PAGE_SIZE + 1,
                sort = sort,
            )
        return page.toCursorPage(
            DEFAULT_PAGE_SIZE,
            totalCount,
            { EvaluationCursor(it.year, it.semester.value, it.id!!, it.likeCount) },
            { it.toDisplay(userId) },
        )
    }

    fun getMyEvaluationsOfCourse(
        userId: Long,
        courseId: Long,
    ): List<EvaluationDisplay> {
        courseRepository.findByIdOrNull(courseId) ?: throw SnuttException(ErrorType.COURSE_NOT_FOUND)
        return evaluationRepository
            .findByCourseIdAndUserIdAndIsHiddenFalseOrderByYearDescSemesterDescIdDesc(courseId, userId)
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
        val moveTo = request.moveToLectureId?.let(::findLecture)
        return updateEvaluation(
            userId,
            evaluationId,
            request,
            moveTo?.courseId,
            moveTo?.year,
            moveTo?.semester,
        )
    }

    @Transactional
    fun updateEvaluationForCourseSemester(
        userId: Long,
        evaluationId: Long,
        request: EvaluationUpdateRequest,
        courseId: Long,
        year: Int,
        semester: Semester,
    ): EvaluationDisplay = updateEvaluation(userId, evaluationId, request, courseId, year, semester)

    private fun updateEvaluation(
        userId: Long,
        evaluationId: Long,
        request: EvaluationUpdateRequest,
        moveToCourseId: Long?,
        moveToYear: Int?,
        moveToSemester: Semester?,
    ): EvaluationDisplay {
        val evaluation =
            evaluationRepository.findByIdAndIsHiddenFalse(evaluationId)
                ?: throw SnuttException(ErrorType.EVALUATION_NOT_FOUND)
        if (evaluation.userId != userId) throw SnuttException(ErrorType.NOT_MY_EVALUATION)
        validateRatings(request.gradeSatisfaction, request.teachingSkill, request.gains, request.lifeBalance, request.rating)

        if (isUpdatingAny(evaluation, request, moveToCourseId, moveToYear, moveToSemester)) {
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
        if (moveToCourseId != null && moveToYear != null && moveToSemester != null) {
            moveTo(evaluation, moveToCourseId, moveToYear, moveToSemester)
        }

        courseAggregateUpdater.update(evaluation.courseId)
        return evaluation.toDisplay(userId)
    }

    private fun moveTo(
        evaluation: Evaluation,
        courseId: Long,
        year: Int,
        semester: Semester,
    ) {
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
        evaluationRepository.incrementLikeCount(evaluationId)
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
        evaluationRepository.decrementLikeCount(evaluationId)
    }

    fun findSummariesByLectureIds(lectureIds: Collection<Long>): Map<Long, EvaluationSummary> =
        evaluationRepository.findSummariesByLectureIds(lectureIds)

    data class LectureEvaluationSummaryDisplay(
        val lecture: Lecture,
        val averages: EvaluationAverages?,
    )

    fun getEvaluationSummaryOfLecture(lectureId: Long): LectureEvaluationSummaryDisplay {
        val lecture =
            lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val courseId = lecture.courseId ?: throw SnuttException(ErrorType.EV_DATA_NOT_FOUND)
        return LectureEvaluationSummaryDisplay(
            lecture = lecture,
            averages = evaluationRepository.findEvaluationAverages(courseId, lecture.year, lecture.semester),
        )
    }

    data class CourseEvaluationSummaryDisplay(
        val course: Course,
        val averages: EvaluationAverages?,
    )

    fun getEvaluationSummaryOfCourse(courseId: Long): CourseEvaluationSummaryDisplay {
        val course = courseRepository.findByIdOrNull(courseId) ?: throw SnuttException(ErrorType.COURSE_NOT_FOUND)
        return CourseEvaluationSummaryDisplay(
            course = course,
            averages = evaluationRepository.findEvaluationAverages(courseId, null, null),
        )
    }

    private fun findLecture(lectureId: Long): Lecture =
        lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)

    private fun validateRatings(vararg ratings: Double?) {
        if (ratings.filterNotNull().any { it < 1.0 || it > 5.0 }) {
            throw SnuttException(ErrorType.EVALUATION_RATING_OUT_OF_RANGE)
        }
    }

    private fun isUpdatingAny(
        evaluation: Evaluation,
        request: EvaluationUpdateRequest,
        moveToCourseId: Long?,
        moveToYear: Int?,
        moveToSemester: Semester?,
    ): Boolean =
        (request.content != null && request.content != evaluation.content) ||
            (request.gradeSatisfaction != null && request.gradeSatisfaction != evaluation.gradeSatisfaction) ||
            (request.teachingSkill != null && request.teachingSkill != evaluation.teachingSkill) ||
            (request.gains != null && request.gains != evaluation.gains) ||
            (request.lifeBalance != null && request.lifeBalance != evaluation.lifeBalance) ||
            (request.rating != null && request.rating != evaluation.rating) ||
            (
                moveToCourseId != null &&
                    moveToYear != null &&
                    moveToSemester != null &&
                    (
                        moveToCourseId != evaluation.courseId ||
                            moveToYear != evaluation.year ||
                            moveToSemester != evaluation.semester
                    )
            )

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
