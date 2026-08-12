package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.querydsl.jpa.impl.JPAQuery
import com.wafflestudio.snutt.core.domain.evaluation.model.QCourse
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.QLecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRatingJoinView
import org.springframework.stereotype.Component

// 검색 평점 정렬용 lecture ⋈ course 조인 뷰 (PLAN.md §2: lecture 도메인은 course를 모른다)
@Component
class CourseRatingJoinView : LectureRatingJoinView {
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
}
