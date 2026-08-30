package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.entity.Entity
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutorImpl
import com.wafflestudio.snutt.core.common.enums.Semester
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

@Repository
class EvaluationRepositoryImpl(
    entityManager: EntityManager,
    context: JpqlRenderContext,
) : EvaluationCustomRepository,
    KotlinJdslJpqlExecutor by KotlinJdslJpqlExecutorImpl(entityManager, context, null) {
    override fun findOthersByCourseAndSemester(
        courseId: Long,
        year: Int?,
        semester: Semester?,
        userId: Long,
        cursor: EvaluationCursor?,
        pageSize: Int,
        sort: EvaluationSort,
    ): List<Evaluation> =
        findAll(offset = null, limit = pageSize) {
            jpql {
                val predicates = mutableListOf<Predicate>()
                predicates += path(Evaluation::courseId).equal(courseId)
                year?.let { predicates += path(Evaluation::year).equal(it) }
                semester?.let { predicates += path(Evaluation::semester).equal(it) }
                predicates += or(path(Evaluation::userId).isNull(), path(Evaluation::userId).notEqual(userId))
                predicates += path(Evaluation::isHidden).equal(false)
                cursor?.let { predicates += beforeCursor(it, sort) }
                select(entity(Evaluation::class))
                    .from(entity(Evaluation::class))
                    .where(and(*predicates.toTypedArray()))
                    .orderBy(*sortOrder(sort).toTypedArray())
            }
        }.filterNotNull()

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
                    .from(entity(Evaluation::class))
                    .where(and(*predicates.toTypedArray()))
                    .orderBy(path(Evaluation::id).desc())
            }
        }.filterNotNull()

    override fun countByCourseIdAndIsHiddenFalse(
        courseId: Long,
        year: Int?,
        semester: Semester?,
    ): Long =
        findAll(offset = null, limit = 1) {
            jpql {
                val predicates = mutableListOf<Predicate>()
                predicates += path(Evaluation::courseId).equal(courseId)
                predicates += path(Evaluation::isHidden).equal(false)
                year?.let { predicates += path(Evaluation::year).equal(it) }
                semester?.let { predicates += path(Evaluation::semester).equal(it) }
                select(count(path(Evaluation::id)))
                    .from(entity(Evaluation::class))
                    .where(and(*predicates.toTypedArray()))
            }
        }.firstOrNull() ?: 0L

    override fun countOthersByCourseIdAndIsHiddenFalse(
        courseId: Long,
        userId: Long,
        year: Int?,
        semester: Semester?,
    ): Long =
        findAll(offset = null, limit = 1) {
            jpql {
                val predicates = mutableListOf<Predicate>()
                predicates += path(Evaluation::courseId).equal(courseId)
                predicates += path(Evaluation::isHidden).equal(false)
                predicates += or(path(Evaluation::userId).isNull(), path(Evaluation::userId).notEqual(userId))
                year?.let { predicates += path(Evaluation::year).equal(it) }
                semester?.let { predicates += path(Evaluation::semester).equal(it) }
                select(count(path(Evaluation::id)))
                    .from(entity(Evaluation::class))
                    .where(and(*predicates.toTypedArray()))
            }
        }.firstOrNull() ?: 0L

    override fun findCourseAggregate(courseId: Long): Pair<Long, Double?> {
        val row =
            findAll(offset = null, limit = 1) {
                jpql {
                    selectNew<AggregateRow>(
                        count(path(Evaluation::id)),
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
        return (row?.count ?: 0L) to row?.avgRating
    }

    override fun findEvaluationAverages(
        courseId: Long,
        year: Int?,
        semester: Semester?,
    ): EvaluationAverages? =
        findAll(offset = null, limit = 1) {
            jpql {
                val predicates = mutableListOf<Predicate>()
                predicates += path(Evaluation::courseId).equal(courseId)
                year?.let { predicates += path(Evaluation::year).equal(it) }
                semester?.let { predicates += path(Evaluation::semester).equal(it) }
                predicates += path(Evaluation::isHidden).equal(false)
                selectNew<EvaluationAverages>(
                    avg(path(Evaluation::gradeSatisfaction)),
                    avg(path(Evaluation::teachingSkill)),
                    avg(path(Evaluation::gains)),
                    avg(path(Evaluation::lifeBalance)),
                    avg(path(Evaluation::rating)),
                ).from(entity(Evaluation::class))
                    .where(and(*predicates.toTypedArray()))
            }
        }.firstOrNull()

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

    override fun incrementLikeCount(id: Long): Int =
        update {
            jpql {
                update(entity(Evaluation::class))
                    .set(path(Evaluation::likeCount), path(Evaluation::likeCount).plus(1L))
                    .where(path(Evaluation::id).equal(id))
            }
        }

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
                        path(Evaluation::semester).lessThan(Semester.fromValue(cursor.semester)),
                    ),
                    and(
                        path(Evaluation::year).equal(cursor.year),
                        path(Evaluation::semester).equal(Semester.fromValue(cursor.semester)),
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
                            .from(entity(Course::class))
                            .where(
                                and(
                                    path(Course::id).equal(path(Evaluation::courseId)),
                                    path(Course::classification).equal("교양"),
                                ),
                            )
                    }.asSubquery(),
                )
            EvaluationTag.RECOMMENDED ->
                courseAvgHaving { innerEvaluation ->
                    avg(innerEvaluation.path(Evaluation::rating)).greaterThanOrEqualTo(4.0)
                }
            EvaluationTag.WELL_TAUGHT ->
                courseAvgHaving { innerEvaluation ->
                    and(
                        avg(innerEvaluation.path(Evaluation::teachingSkill)).greaterThanOrEqualTo(4.0),
                        avg(innerEvaluation.path(Evaluation::gains)).greaterThanOrEqualTo(4.0),
                    )
                }
            EvaluationTag.SWEET ->
                courseAvgHaving { innerEvaluation ->
                    and(
                        avg(innerEvaluation.path(Evaluation::gradeSatisfaction)).greaterThanOrEqualTo(4.0),
                        avg(innerEvaluation.path(Evaluation::lifeBalance)).greaterThanOrEqualTo(4.0),
                    )
                }
            EvaluationTag.HARD_BUT_WORTH ->
                courseAvgHaving { innerEvaluation ->
                    and(
                        avg(innerEvaluation.path(Evaluation::lifeBalance)).lessThan(2.0),
                        avg(innerEvaluation.path(Evaluation::gains)).greaterThanOrEqualTo(4.0),
                    )
                }
        }

    private fun Jpql.courseAvgHaving(having: Jpql.(Entity<Evaluation>) -> Predicate): Predicate =
        exists(
            jpql {
                val innerEvaluation = entity(Evaluation::class, "innerEvaluation")
                select(innerEvaluation.path(Evaluation::courseId))
                    .from(innerEvaluation)
                    .where(
                        and(
                            innerEvaluation.path(Evaluation::courseId).equal(path(Evaluation::courseId)),
                            innerEvaluation.path(Evaluation::isHidden).equal(false),
                        ),
                    ).groupBy(innerEvaluation.path(Evaluation::courseId))
                    .having(having(innerEvaluation))
            }.asSubquery(),
        )

    private data class AggregateRow(
        val count: Long,
        val avgRating: Double?,
    )
}
