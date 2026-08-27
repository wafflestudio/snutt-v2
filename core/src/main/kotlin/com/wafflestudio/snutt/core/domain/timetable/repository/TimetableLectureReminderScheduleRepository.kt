package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminderSchedule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TimetableLectureReminderScheduleRepository : JpaRepository<TimetableLectureReminderSchedule, Long> {
    fun findByReminderId(reminderId: Long): List<TimetableLectureReminderSchedule>

    fun findByReminderIdIn(reminderIds: Collection<Long>): List<TimetableLectureReminderSchedule>

    fun deleteByReminderId(reminderId: Long)

    // day/minute 복합 인덱스로 스케줄러가 발화 대상 스케줄을 범위 조회한다
    @Query(
        "SELECT s.reminderId FROM TimetableLectureReminderSchedule s " +
            "WHERE s.day = :day AND s.minute BETWEEN :startMinute AND :endMinute",
    )
    fun findReminderIdsByFireInRange(
        day: DayOfWeek,
        startMinute: Int,
        endMinute: Int,
    ): List<Long>
}
