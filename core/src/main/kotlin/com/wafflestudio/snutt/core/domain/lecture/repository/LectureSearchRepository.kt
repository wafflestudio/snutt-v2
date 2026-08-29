package com.wafflestudio.snutt.core.domain.lecture.repository

import com.querydsl.jpa.impl.JPAQuery
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture

interface LectureSearchRepository {
    fun search(
        criteria: LectureSearchCriteria,
        cursorLectureId: Long?,
        limit: Int,
    ): List<Lecture>
}

interface LectureRatingJoinView {
    fun applyOrderBy(
        query: JPAQuery<Lecture>,
        sort: LectureSort,
    ): JPAQuery<Lecture>

    fun applyCursor(
        query: JPAQuery<Lecture>,
        sort: LectureSort,
        cursorLectureId: Long,
    ): JPAQuery<Lecture>
}
