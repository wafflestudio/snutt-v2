package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutorImpl
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseAggregate
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationAverages
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSort
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSummary
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class EvaluationRepositoryImpl(
    entityManager: EntityManager,
    context: JpqlRenderContext,
) : EvaluationCustomRepository,
    KotlinJdslJpqlExecutor by KotlinJdslJpqlExecutorImpl(entityManager, context, null) {
    override fun findOthers(
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

    override fun findMine(
        userId: Long,
        cursorId: Long?,
        pageSize: Int,
    ): List<Evaluation> =
        findAll(offset = null, limit = pageSize) {
            jpql {
                val predicates = mutableListOf<Predicate>()
                predicates += path(Evaluation::userId).equal(userId)
                predicates += path(Evaluation::isHidden).equal(false)
                cursorId?.let { predicates += path(Evaluation::id).lessThan(it) }
                select(entity(Evaluation::class))
                    .from(entity(Evaluation::class))
                    .where(and(*predicates.toTypedArray()))
                    .orderBy(path(Evaluation::id).desc())
            }
        }.filterNotNull()

    override fun findByTag(
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

    override fun countVisible(
        courseId: Long,
        year: Int?,
        semester: Semester?,
    ): Long =
        findAll(offset = null, limit = 1) {
            jpql {
                select(count(path(Evaluation::id)))
                    .from(entity(Evaluation::class))
                    .where(visibleOf(courseId, year, semester))
            }
        }.firstOrNull() ?: 0L

    override fun findCourseAggregate(courseId: Long): CourseAggregate {
        val row =
            findAll(offset = null, limit = 1) {
                jpql {
                    selectNew<AggregateRow>(
                        count(path(Evaluation::id)),
                        avg(path(Evaluation::gradeSatisfaction)),
                        avg(path(Evaluation::teachingSkill)),
                        avg(path(Evaluation::gains)),
                        avg(path(Evaluation::lifeBalance)),
                        avg(path(Evaluation::rating)),
                    ).from(entity(Evaluation::class))
                        .where(
                            and(
                                path(Evaluation::courseId).equal(courseId),
                                path(Evaluation::isHidden).equal(false),
                            ),
                        )
                }
            }.firstOrNull()
        return CourseAggregate(
            evalCount = row?.count ?: 0L,
            averages =
                EvaluationAverages(
                    avgGradeSatisfaction = row?.avgGradeSatisfaction,
                    avgTeachingSkill = row?.avgTeachingSkill,
                    avgGains = row?.avgGains,
                    avgLifeBalance = row?.avgLifeBalance,
                    avgRating = row?.avgRating,
                ),
        )
    }

    override fun findSummariesByLectureIds(lectureIds: Collection<Long>): Map<Long, EvaluationSummary> {
        if (lectureIds.isEmpty()) return emptyMap()
        return findAll(offset = null, limit = null) {
            jpql {
                selectNew<SummaryRow>(
                    path(Lecture::id),
                    path(Course::avgRating),
                    coalesce(path(Course::evalCount), 0L),
                ).from(
                    entity(Lecture::class),
                    leftJoin(Course::class).on(path(Lecture::courseId).equal(path(Course::id))),
                ).where(path(Lecture::id).`in`(lectureIds))
            }
        }.filterNotNull()
            .mapNotNull { row -> row.lectureId?.let { it to EvaluationSummary(row.avgRating, row.evalCount) } }
            .toMap()
    }

    private data class SummaryRow(
        val lectureId: Long?,
        val avgRating: Double?,
        val evalCount: Long,
    )

    override fun findEvaluatedCourseSemesters(
        userId: Long,
        courseIds: Collection<Long>,
    ): List<EvaluatedCourseSemester> {
        if (courseIds.isEmpty()) return emptyList()
        return findAll(offset = null, limit = null) {
            jpql {
                selectNew<EvaluatedCourseSemester>(
                    path(Evaluation::courseId),
                    path(Evaluation::year),
                    path(Evaluation::semester),
                ).from(entity(Evaluation::class))
                    .where(
                        and(
                            path(Evaluation::userId).equal(userId),
                            path(Evaluation::courseId).`in`(courseIds),
                            path(Evaluation::isHidden).equal(false),
                        ),
                    )
            }
        }.filterNotNull()
    }

    @Transactional
    override fun incrementLikeCount(id: Long): Int =
        update {
            jpql {
                update(entity(Evaluation::class))
                    .set(path(Evaluation::likeCount), path(Evaluation::likeCount).plus(1L))
                    .where(path(Evaluation::id).equal(id))
            }
        }

    @Transactional
    override fun decrementLikeCount(id: Long): Int =
        update {
            jpql {
                update(entity(Evaluation::class))
                    .set(
                        path(Evaluation::likeCount),
                        caseWhen(path(Evaluation::likeCount).greaterThan(0L))
                            .then(path(Evaluation::likeCount).minus(1L))
                            .`else`(0L),
                    ).where(path(Evaluation::id).equal(id))
            }
        }

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

    private data class AggregateRow(
        val count: Long,
        val avgGradeSatisfaction: Double?,
        val avgTeachingSkill: Double?,
        val avgGains: Double?,
        val avgLifeBalance: Double?,
        val avgRating: Double?,
    )
}
