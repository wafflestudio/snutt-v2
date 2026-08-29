package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.model.QCourse
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.QLecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRatingJoinView
import org.springframework.stereotype.Component

@Component
class CourseRatingJoinView(
    private val queryFactory: JPAQueryFactory,
) : LectureRatingJoinView {
    override fun applyOrderBy(
        query: JPAQuery<Lecture>,
        sort: LectureSort,
    ): JPAQuery<Lecture> {
        val lecture = QLecture.lecture
        val course = QCourse.course
        query.leftJoin(course).on(course.id.eq(lecture.courseId))
        return when (sort) {
            // 미연결 강의는 eval_count 0으로 취급 (스키마 DEFAULT 0). MySQL DESC는 NULL을
            // 마지막에 정렬하므로 coalesce로 Mongo evInfo.count=0 시맨틱과 맞춘다
            LectureSort.RATING_DESC -> query.orderBy(course.avgRating.desc(), lecture.id.asc())
            LectureSort.COUNT_DESC -> query.orderBy(course.evalCount.coalesce(0L).desc(), lecture.id.asc())
            LectureSort.DEFAULT -> query
        }
    }

    override fun applyCursor(
        query: JPAQuery<Lecture>,
        sort: LectureSort,
        cursorLectureId: Long,
    ): JPAQuery<Lecture> {
        val lecture = QLecture.lecture
        val course = QCourse.course
        val cursor =
            queryFactory
                .select(course.avgRating, course.evalCount.coalesce(0L))
                .from(lecture)
                .leftJoin(course)
                .on(course.id.eq(lecture.courseId))
                .where(lecture.id.eq(cursorLectureId))
                .fetchOne()
                ?: throw SnuttException(ErrorType.INVALID_CURSOR)
        return when (sort) {
            LectureSort.RATING_DESC -> {
                val rating = cursor.get(course.avgRating)
                if (rating == null) {
                    query.where(course.avgRating.isNull.and(lecture.id.gt(cursorLectureId)))
                } else {
                    query.where(
                        course.avgRating.lt(rating).or(course.avgRating.isNull).or(
                            course.avgRating.eq(rating).and(lecture.id.gt(cursorLectureId)),
                        ),
                    )
                }
            }

            LectureSort.COUNT_DESC -> {
                val count = checkNotNull(cursor.get(course.evalCount.coalesce(0L)))
                query.where(
                    course.evalCount
                        .coalesce(0L)
                        .lt(count)
                        .or(
                            course.evalCount
                                .coalesce(0L)
                                .eq(count)
                                .and(lecture.id.gt(cursorLectureId)),
                        ),
                )
            }

            LectureSort.DEFAULT -> query
        }
    }
}
