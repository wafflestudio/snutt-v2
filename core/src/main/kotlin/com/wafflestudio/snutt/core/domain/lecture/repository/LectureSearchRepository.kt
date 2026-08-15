package com.wafflestudio.snutt.core.domain.lecture.repository

import com.querydsl.jpa.impl.JPAQuery
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture

interface LectureSearchRepository {
    fun search(criteria: LectureSearchCriteria): List<Lecture>
}

// 검색의 lecture ⋈ course 조인. 구현은 평가 도메인이 제공한다 (
// lecture 도메인은 course를 모르며, 평점 정렬만 조인 뷰를 통해 주입받는다)
interface LectureRatingJoinView {
    fun applyOrderBy(
        query: JPAQuery<Lecture>,
        sort: LectureSort,
    ): JPAQuery<Lecture>
}
