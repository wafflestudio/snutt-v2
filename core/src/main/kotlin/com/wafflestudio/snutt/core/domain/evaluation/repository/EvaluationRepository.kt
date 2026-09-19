package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseAggregate
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationAverages
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSort
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSummary
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface EvaluationRepository :
    JpaRepository<Evaluation, Long>,
    EvaluationCustomRepository {
    fun existsByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(
        courseId: Long,
        year: Int,
        semester: Semester,
        userId: Long,
    ): Boolean

    fun findByCourseIdAndUserIdAndIsHiddenFalseOrderByYearDescSemesterDescIdDesc(
        courseId: Long,
        userId: Long,
    ): List<Evaluation>

    fun countByUserIdAndIsHiddenFalse(userId: Long): Long

    fun findByIdAndIsHiddenFalse(id: Long): Evaluation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Evaluation e WHERE e.id = :id AND e.isHidden = false")
    fun findForUpdate(id: Long): Evaluation?
}

interface EvaluationCustomRepository {
    fun incrementLikeCount(id: Long): Int

    fun decrementLikeCount(id: Long): Int

    fun findEvaluatedCourseSemesters(
        userId: Long,
        courseIds: Collection<Long>,
    ): List<EvaluatedCourseSemester>

    fun findSummariesByLectureIds(lectureIds: Collection<Long>): Map<Long, EvaluationSummary>

    fun findOthersByCourseAndSemester(
        courseId: Long,
        year: Int?,
        semester: Semester?,
        userId: Long,
        cursor: EvaluationCursor?,
        pageSize: Int,
        sort: EvaluationSort = EvaluationSort.LATEST,
    ): List<Evaluation>

    fun findMine(
        userId: Long,
        cursorId: Long?,
        pageSize: Int,
    ): List<Evaluation>

    fun findByTag(
        tag: EvaluationTag,
        cursorId: Long?,
        pageSize: Int,
    ): List<Evaluation>

    fun findCourseAggregate(courseId: Long): CourseAggregate

    fun findEvaluationAverages(
        courseId: Long,
        year: Int?,
        semester: Semester?,
    ): EvaluationAverages?

    fun countByCourseIdAndIsHiddenFalse(
        courseId: Long,
        year: Int? = null,
        semester: Semester? = null,
    ): Long

    fun countOthersByCourseIdAndIsHiddenFalse(
        courseId: Long,
        userId: Long,
        year: Int? = null,
        semester: Semester? = null,
    ): Long
}
