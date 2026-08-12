package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import org.springframework.data.jpa.repository.JpaRepository

interface TimetableLectureRepository : JpaRepository<TimetableLecture, Long> {
    fun findByTimetableId(timetableId: Long): List<TimetableLecture>

    fun findByTimetableIdIn(timetableIds: Collection<Long>): List<TimetableLecture>

    fun findByTimetableIdAndExternalId(
        timetableId: Long,
        externalId: String,
    ): TimetableLecture?

    fun findByLectureIdIn(lectureIds: Collection<Long>): List<TimetableLecture>
}
