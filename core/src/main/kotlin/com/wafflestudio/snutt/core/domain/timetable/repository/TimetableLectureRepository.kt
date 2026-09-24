package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface TimetableLectureRepository : JpaRepository<TimetableLecture, Long> {
    fun findByTimetableId(timetableId: Long): List<TimetableLecture>

    fun findByIdAndTimetableId(
        id: Long,
        timetableId: Long,
    ): TimetableLecture?

    fun findByTimetableIdIn(timetableIds: Collection<Long>): List<TimetableLecture>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByTimetableIdIn(timetableIds: Collection<Long>): List<TimetableLecture>

    fun findByLectureIdIn(lectureIds: Collection<Long>): List<TimetableLecture>

    @Query("SELECT DISTINCT tl.timetableId FROM TimetableLecture tl WHERE tl.lectureId IN :lectureIds")
    fun findTimetableIdsByLectureIdIn(lectureIds: Collection<Long>): List<Long>

    fun deleteByTimetableIdAndId(
        timetableId: Long,
        id: Long,
    )
}
