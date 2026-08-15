package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface TimetableLectureReminderRepository : JpaRepository<TimetableLectureReminder, Long> {
    fun findByTimetableLectureId(timetableLectureId: Long): TimetableLectureReminder?

    @Query(
        "SELECT r FROM TimetableLectureReminder r WHERE r.nextDay = :day " +
            "AND r.nextMinute BETWEEN :startMinute AND :endMinute " +
            "AND (r.recentNotifiedAt IS NULL OR r.recentNotifiedAt < :lastNotifiedBefore)",
    )
    fun findDueRemindersInTimeRange(
        day: Int,
        startMinute: Int,
        endMinute: Int,
        lastNotifiedBefore: Instant,
    ): List<TimetableLectureReminder>

    fun findByTimetableLectureIdIn(timetableLectureIds: Collection<Long>): List<TimetableLectureReminder>

    fun deleteByTimetableLectureId(timetableLectureId: Long)
}
