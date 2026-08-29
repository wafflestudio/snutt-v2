package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationAverages
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSort
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSummary
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface EvaluatedCourseSemester {
    val courseId: Long
    val year: Int
    val semester: Semester
}

interface EvaluationRepository :
    JpaRepository<Evaluation, Long>,
    EvaluationCustomRepository {
    @Modifying
    @Query("UPDATE Evaluation e SET e.likeCount = e.likeCount + 1 WHERE e.id = :id")
    fun incrementLikeCount(id: Long)

    @Modifying
    @Query("UPDATE Evaluation e SET e.likeCount = GREATEST(e.likeCount - 1, 0) WHERE e.id = :id")
    fun decrementLikeCount(id: Long)

    fun existsByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(
        courseId: Long,
        year: Int,
        semester: Semester,
        userId: Long,
    ): Boolean

    @Query(
        "SELECT e.courseId AS courseId, e.year AS year, e.semester AS semester FROM Evaluation e " +
            "WHERE e.userId = :userId AND e.courseId IN :courseIds AND e.isHidden = false",
    )
    fun findEvaluatedCourseSemesters(
        userId: Long,
        courseIds: Collection<Long>,
    ): List<EvaluatedCourseSemester>

    fun findByCourseIdAndUserIdAndIsHiddenFalseOrderByYearDescSemesterDescIdDesc(
        courseId: Long,
        userId: Long,
    ): List<Evaluation>

    fun countByUserIdAndIsHiddenFalse(userId: Long): Long

    fun findByIdAndIsHiddenFalse(id: Long): Evaluation?
}

interface EvaluationCustomRepository {
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

    fun findCourseAggregate(courseId: Long): Pair<Long, Double?>

    fun findEvaluationAverages(
        courseId: Long,
        year: Int?,
        semester: Semester?,
    ): EvaluationAverages?

    fun findSummariesByLectureIds(lectureIds: Collection<Long>): Map<Long, EvaluationSummary>

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
