package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import org.springframework.data.jpa.repository.JpaRepository

interface TimetableLectureReminderRepository : JpaRepository<TimetableLectureReminder, Long> {
    fun findByTimetableLectureId(timetableLectureId: Long): TimetableLectureReminder?

    fun findByTimetableLectureIdIn(timetableLectureIds: Collection<Long>): List<TimetableLectureReminder>

    fun deleteByTimetableLectureId(timetableLectureId: Long)
}
