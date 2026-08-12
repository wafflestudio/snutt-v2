package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationAverages
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSummary
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.tag.model.Tag
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
    // (course, year, semester) 강의평 목록: 내 것 제외, (year desc, semester desc, id desc) keyset
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
        tag: Tag,
        cursorId: Long?,
        pageSize: Int,
    ): List<Evaluation>

    // is_hidden=false 강의평의 course 집계 (평점 비정규화 재계산용)
    fun findCourseAggregate(courseId: Long): Pair<Long, Double?>

    // 강의평 요약: (course, year, semester)별 평균
    fun findEvaluationAverages(
        courseId: Long,
        year: Int,
        semester: Semester,
    ): EvaluationAverages?

    // 검색 DTO의 ev summary 조인: lecture id → course 집계 (PLAN.md §7 M4)
    fun findSummariesByLectureIds(lectureIds: Collection<Long>): Map<Long, EvaluationSummary>
}
