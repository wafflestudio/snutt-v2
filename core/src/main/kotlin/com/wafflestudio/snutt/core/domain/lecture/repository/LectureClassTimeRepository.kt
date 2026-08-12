package com.wafflestudio.snutt.core.domain.lecture.repository

import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import org.springframework.data.jpa.repository.JpaRepository

interface LectureClassTimeRepository : JpaRepository<LectureClassTime, Long> {
    fun deleteByLectureId(lectureId: Long)
}
