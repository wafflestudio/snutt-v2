package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationAverages
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSummary
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import org.springframework.data.jpa.repository.JpaRepository

interface EvaluationRepository :
    JpaRepository<Evaluation, Long>,
    EvaluationCustomRepository {
    fun existsByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(
        courseId: Long,
        year: Int,
        semester: Semester,
        userId: Long,
    ): Boolean

    fun findByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(
        courseId: Long,
        year: Int,
        semester: Semester,
        userId: Long,
    ): List<Evaluation>

    fun countByCourseIdAndYearAndSemesterAndIsHiddenFalse(
        courseId: Long,
        year: Int,
        semester: Semester,
    ): Long

    fun countByUserIdAndIsHiddenFalse(userId: Long): Long

    fun findByIdAndIsHiddenFalse(id: Long): Evaluation?
}

interface EvaluationCustomRepository {
    fun findOthersByCourseAndSemester(
        courseId: Long,
        year: Int,
        semester: Semester,
        userId: Long,
        cursor: EvaluationCursor?,
        pageSize: Int,
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

    fun findCourseAggregate(courseId: Long): Pair<Long, Double?>

    fun findEvaluationAverages(
        courseId: Long,
        year: Int,
        semester: Semester,
    ): EvaluationAverages?

    fun findSummariesByLectureIds(lectureIds: Collection<Long>): Map<Long, EvaluationSummary>
}
