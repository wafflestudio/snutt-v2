package com.wafflestudio.snutt.core.domain.lecture.repository

import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture

data class LectureSearchRow(
    val lecture: Lecture,
    val evalCount: Long,
    val avgRating: Double?,
)

interface LectureSearchRepository {
    fun search(
        criteria: LectureSearchCriteria,
        cursorLectureId: Long?,
        limit: Int,
    ): List<LectureSearchRow>
}
