package com.wafflestudio.snutt.core.domain.lecture.repository

import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface LectureClassTimeRepository : JpaRepository<LectureClassTime, Long> {
    @Modifying
    @Query("DELETE FROM LectureClassTime c WHERE c.lectureId IN :lectureIds")
    fun deleteByLectureIdIn(lectureIds: Collection<Long>)

    fun findAllByLectureIdInOrderById(lectureIds: Collection<Long>): List<LectureClassTime>
}
