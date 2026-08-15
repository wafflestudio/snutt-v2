package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface TimetableLectureReminderRepository : JpaRepository<TimetableLectureReminder, Long> {
    fun findByTimetableLectureId(timetableLectureId: Long): TimetableLectureReminder?

    /**
     * 발화 예정 시각이 [startMinute, endMinute] 창 안이고 아직 안 보낸 리마인더.
     * 정확-분 매칭 대신 창 조회를 써서 스케줄러가 몇 분 멈춰도 따라잡는다 (v1 동일)
     */
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
