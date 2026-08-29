package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.querydsl.core.types.Predicate
import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.jpa.JPAExpressions
import com.querydsl.jpa.impl.JPAQueryFactory
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationAverages
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationCursor
import com.wafflestudio.snutt.core.domain.evaluation.dto.EvaluationSummary
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.evaluation.model.QCourse
import com.wafflestudio.snutt.core.domain.evaluation.model.QEvaluation
import com.wafflestudio.snutt.core.domain.lecture.model.QLecture
import org.springframework.stereotype.Repository

@Repository
class EvaluationRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : EvaluationCustomRepository {
    private val evaluation = QEvaluation.evaluation

    private val innerEvaluation = QEvaluation("evaluation2")

    override fun findOthersByCourseAndSemester(
        courseId: Long,
        year: Int,
        semester: Semester,
        userId: Long,
        cursor: EvaluationCursor?,
        pageSize: Int,
    ): List<Evaluation> =
        queryFactory
            .selectFrom(evaluation)
            .where(
                evaluation.courseId.eq(courseId),
                evaluation.year.eq(year),
                evaluation.semester.eq(semester),
                evaluation.userId.isNull.or(evaluation.userId.ne(userId)),
                evaluation.isHidden.isFalse,
                cursor?.let { beforeCursor(it) },
            ).orderBy(evaluation.year.desc(), evaluation.semester.desc(), evaluation.id.desc())
            .limit(pageSize.toLong())
            .fetch()

    override fun findMine(
        userId: Long,
        cursorId: Long?,
        pageSize: Int,
    ): List<Evaluation> =
        queryFactory
            .selectFrom(evaluation)
            .where(
                evaluation.userId.eq(userId),
                evaluation.isHidden.isFalse,
                cursorId?.let { evaluation.id.lt(it) },
            ).orderBy(evaluation.id.desc())
            .limit(pageSize.toLong())
            .fetch()

    override fun findByTag(
        tag: EvaluationTag,
        cursorId: Long?,
        pageSize: Int,
    ): List<Evaluation> =
        queryFactory
            .selectFrom(evaluation)
            .where(
                evaluation.isHidden.isFalse,
                cursorId?.let { evaluation.id.lt(it) },
                tagPredicate(tag),
            ).orderBy(evaluation.id.desc())
            .limit(pageSize.toLong())
            .fetch()

    override fun findCourseAggregate(courseId: Long): Pair<Long, Double?> {
        val row =
            queryFactory
                .select(evaluation.id.count(), evaluation.rating.avg())
                .from(evaluation)
                .where(evaluation.courseId.eq(courseId), evaluation.isHidden.isFalse)
                .fetchOne()
        return (row?.get(0, Long::class.java) ?: 0L) to row?.get(1, Double::class.java)
    }

    override fun findEvaluationAverages(
        courseId: Long,
        year: Int,
        semester: Semester,
    ): EvaluationAverages? {
        val row =
            queryFactory
                .select(
                    evaluation.gradeSatisfaction.avg(),
                    evaluation.teachingSkill.avg(),
                    evaluation.gains.avg(),
                    evaluation.lifeBalance.avg(),
                    evaluation.rating.avg(),
                ).from(evaluation)
                .where(
                    evaluation.courseId.eq(courseId),
                    evaluation.year.eq(year),
                    evaluation.semester.eq(semester),
                    evaluation.isHidden.isFalse,
                ).fetchOne()
                ?: return null
        return EvaluationAverages(
            avgGradeSatisfaction = row.get(0, Double::class.java),
            avgTeachingSkill = row.get(1, Double::class.java),
            avgGains = row.get(2, Double::class.java),
            avgLifeBalance = row.get(3, Double::class.java),
            avgRating = row.get(4, Double::class.java),
        )
    }

    override fun findSummariesByLectureIds(lectureIds: Collection<Long>): Map<Long, EvaluationSummary> {
        if (lectureIds.isEmpty()) return emptyMap()
        val lecture = QLecture.lecture
        val course = QCourse.course
        return queryFactory
            .select(lecture.id, course.avgRating, course.evalCount)
            .from(lecture)
            .leftJoin(course)
            .on(course.id.eq(lecture.courseId))
            .where(lecture.id.`in`(lectureIds))
            .fetch()
            .associate { row ->
                checkNotNull(row.get(lecture.id)) to
                    EvaluationSummary(
                        avgRating = row.get(course.avgRating),
                        evalCount = row.get(course.evalCount) ?: 0L,
                    )
            }
    }

    private fun beforeCursor(cursor: EvaluationCursor): BooleanExpression =
        evaluation.year
            .lt(cursor.year)
            .or(
                evaluation.year
                    .eq(cursor.year)
                    .and(evaluation.semester.lt(Semester.fromValue(cursor.semester))),
            ).or(
                evaluation.year
                    .eq(cursor.year)
                    .and(evaluation.semester.eq(Semester.fromValue(cursor.semester)))
                    .and(evaluation.id.lt(cursor.evaluationId)),
            )

    private fun tagPredicate(tag: EvaluationTag): BooleanExpression? =
        when (tag) {
            EvaluationTag.RECENT -> null
            EvaluationTag.LIBERAL_EDUCATION ->
                JPAExpressions
                    .selectOne()
                    .from(QCourse.course)
                    .where(QCourse.course.id.eq(evaluation.courseId), QCourse.course.classification.eq("교양"))
                    .exists()

            EvaluationTag.RECOMMENDED -> existsWithAvg(innerEvaluation.rating.avg().goe(4.0))
            EvaluationTag.WELL_TAUGHT ->
                existsWithAvg(
                    innerEvaluation.teachingSkill
                        .avg()
                        .goe(4.0)
                        .and(innerEvaluation.gains.avg().goe(4.0)),
                )
            EvaluationTag.SWEET ->
                existsWithAvg(
                    innerEvaluation.gradeSatisfaction
                        .avg()
                        .goe(4.0)
                        .and(innerEvaluation.lifeBalance.avg().goe(4.0)),
                )
            EvaluationTag.HARD_BUT_WORTH ->
                existsWithAvg(
                    innerEvaluation.lifeBalance
                        .avg()
                        .lt(2.0)
                        .and(innerEvaluation.gains.avg().goe(4.0)),
                )
        }

    private fun existsWithAvg(having: Predicate): BooleanExpression =
        JPAExpressions
            .selectOne()
            .from(innerEvaluation)
            .where(
                innerEvaluation.courseId.eq(evaluation.courseId),
                innerEvaluation.isHidden.isFalse,
            ).groupBy(innerEvaluation.courseId)
            .having(having)
            .exists()
}
