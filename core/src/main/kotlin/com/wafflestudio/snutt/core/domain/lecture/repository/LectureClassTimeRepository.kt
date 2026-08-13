package com.wafflestudio.snutt.core.domain.lecture.repository

import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import org.springframework.data.jpa.repository.JpaRepository

interface LectureClassTimeRepository : JpaRepository<LectureClassTime, Long> {
    fun deleteByLectureId(lectureId: Long)

    // 삽입 순서(id)가 강의의 시간 목록 순서와 같다
    fun findAllByLectureIdInOrderById(lectureIds: Collection<Long>): List<LectureClassTime>
}
