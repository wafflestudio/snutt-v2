package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseAggregate
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSort
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface EvaluationRepository :
    JpaRepository<Evaluation, Long>,
    KotlinJdslJpqlExecutor {
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
    fun findForUpdateByIdAndIsHiddenFalse(id: Long): Evaluation?

    @Query(
        "SELECT new com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluatedCourseSemester(e.courseId, e.year, e.semester) " +
            "FROM Evaluation e WHERE e.userId = :userId AND e.courseId IN :courseIds AND e.isHidden = false",
    )
    fun findEvaluatedCourseSemesters(
        userId: Long,
        courseIds: Collection<Long>,
    ): List<EvaluatedCourseSemester>

    @Query(
        "SELECT e FROM Evaluation e WHERE e.userId = :userId AND e.isHidden = false " +
            "AND (:cursorId IS NULL OR e.id < :cursorId) ORDER BY e.id DESC",
    )
    fun findMine(
        userId: Long,
        cursorId: Long?,
        limit: Limit,
    ): List<Evaluation>

    @Query(
        "SELECT new com.wafflestudio.snutt.core.domain.evaluation.dto.CourseAggregate(" +
            "COUNT(e), AVG(e.gradeSatisfaction), AVG(e.teachingSkill), AVG(e.gains), AVG(e.lifeBalance), AVG(e.rating)) " +
            "FROM Evaluation e WHERE e.courseId = :courseId AND e.isHidden = false",
    )
    fun findCourseAggregate(courseId: Long): CourseAggregate

    @Query(
        "SELECT COUNT(e) FROM Evaluation e WHERE e.courseId = :courseId AND e.isHidden = false " +
            "AND (:year IS NULL OR e.year = :year) AND (:semester IS NULL OR e.semester = :semester)",
    )
    fun countVisible(
        courseId: Long,
        year: Int?,
        semester: Semester?,
    ): Long

    fun findOthers(
        courseId: Long,
        userId: Long,
        year: Int?,
        semester: Semester?,
        cursor: EvaluationCursor?,
        pageSize: Int,
        sort: EvaluationSort,
    ): List<Evaluation> =
        findAll(offset = null, limit = pageSize) {
            jpql {
                select(entity(Evaluation::class))
                    .from(entity(Evaluation::class))
                    .where(and(othersOf(courseId, userId, year, semester), cursor?.let { beforeCursor(it, sort) }))
                    .orderBy(*sortOrder(sort).toTypedArray())
            }
        }.filterNotNull()

    private fun Jpql.othersOf(
        courseId: Long,
        userId: Long,
        year: Int?,
        semester: Semester?,
    ): Predicate =
        and(
            visibleOf(courseId, year, semester),
            or(path(Evaluation::userId).isNull(), path(Evaluation::userId).notEqual(userId)),
        )

    private fun Jpql.visibleOf(
        courseId: Long,
        year: Int?,
        semester: Semester?,
    ): Predicate =
        and(
            path(Evaluation::courseId).equal(courseId),
            path(Evaluation::isHidden).equal(false),
            year?.let { path(Evaluation::year).equal(it) },
            semester?.let { path(Evaluation::semester).equal(it) },
        )

    fun findByTag(
        tag: EvaluationTag,
        cursorId: Long?,
        pageSize: Int,
    ): List<Evaluation> =
        findAll(offset = null, limit = pageSize) {
            jpql {
                val predicates = mutableListOf<Predicate>()
                predicates += path(Evaluation::isHidden).equal(false)
                cursorId?.let { predicates += path(Evaluation::id).lessThan(it) }
                tagPredicate(tag)?.let { predicates += it }
                select(entity(Evaluation::class))
                    .from(
                        entity(Evaluation::class),
                        join(Course::class).on(path(Course::id).equal(path(Evaluation::courseId))),
                    ).where(and(*predicates.toTypedArray()))
                    .orderBy(path(Evaluation::id).desc())
            }
        }.filterNotNull()

    private fun Jpql.beforeCursor(
        cursor: EvaluationCursor,
        sort: EvaluationSort,
    ): Predicate =
        when (sort) {
            EvaluationSort.LATEST ->
                or(
                    path(Evaluation::year).lessThan(cursor.year),
                    and(
                        path(Evaluation::year).equal(cursor.year),
                        path(Evaluation::semester).lessThan(cursor.semester),
                    ),
                    and(
                        path(Evaluation::year).equal(cursor.year),
                        path(Evaluation::semester).equal(cursor.semester),
                        path(Evaluation::id).lessThan(cursor.evaluationId),
                    ),
                )
            EvaluationSort.RECOMMENDED -> {
                val likeCount = cursor.likeCount ?: 0
                or(
                    path(Evaluation::likeCount).lessThan(likeCount),
                    and(path(Evaluation::likeCount).equal(likeCount), path(Evaluation::id).lessThan(cursor.evaluationId)),
                )
            }
        }

    private fun Jpql.sortOrder(sort: EvaluationSort) =
        when (sort) {
            EvaluationSort.LATEST ->
                listOf(path(Evaluation::year).desc(), path(Evaluation::semester).desc(), path(Evaluation::id).desc())
            EvaluationSort.RECOMMENDED ->
                listOf(path(Evaluation::likeCount).desc(), path(Evaluation::id).desc())
        }

    private fun Jpql.tagPredicate(tag: EvaluationTag): Predicate? =
        when (tag) {
            EvaluationTag.RECENT -> null
            EvaluationTag.LIBERAL_EDUCATION ->
                exists(
                    jpql {
                        select(value(1))
                            .from(entity(Lecture::class))
                            .where(
                                and(
                                    path(Lecture::courseId).equal(path(Evaluation::courseId)),
                                    path(Lecture::classification).equal("교양"),
                                ),
                            )
                    }.asSubquery(),
                )
            EvaluationTag.RECOMMENDED -> path(Course::avgRating).greaterThanOrEqualTo(4.0)
            EvaluationTag.WELL_TAUGHT ->
                and(
                    path(Course::avgTeachingSkill).greaterThanOrEqualTo(4.0),
                    path(Course::avgGains).greaterThanOrEqualTo(4.0),
                )
            EvaluationTag.SWEET ->
                and(
                    path(Course::avgGradeSatisfaction).greaterThanOrEqualTo(4.0),
                    path(Course::avgLifeBalance).greaterThanOrEqualTo(4.0),
                )
            EvaluationTag.HARD_BUT_WORTH ->
                and(
                    path(Course::avgLifeBalance).lessThan(2.0),
                    path(Course::avgGains).greaterThanOrEqualTo(4.0),
                )
        }
}
