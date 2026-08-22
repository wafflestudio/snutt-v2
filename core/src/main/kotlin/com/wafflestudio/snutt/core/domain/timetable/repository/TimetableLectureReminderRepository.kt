package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface TimetableLectureReminderRepository : JpaRepository<TimetableLectureReminder, Long> {
    fun findByTimetableLectureId(timetableLectureId: Long): TimetableLectureReminder?

    // 발화 여부(스케줄별 최근 알림)는 스케줄러에서 스케줄 단위로 판단한다
    @Query(
        "SELECT r FROM TimetableLectureReminder r WHERE r.nextDay = :day " +
            "AND r.nextMinute BETWEEN :startMinute AND :endMinute",
    )
    fun findByNextFireInRange(
        day: Int,
        startMinute: Int,
        endMinute: Int,
    ): List<TimetableLectureReminder>

    @Modifying
    @Transactional
    @Query(
        value =
            "DELETE r FROM timetable_lecture_reminder r " +
                "JOIN timetable_lecture tl ON tl.id = r.timetable_lecture_id " +
                "JOIN timetable t ON t.id = tl.timetable_id " +
                "WHERE t.year < :year OR (t.year = :year AND t.semester < :semesterValue)",
        nativeQuery = true,
    )
    fun deleteByPastSemesters(
        year: Int,
        semesterValue: Int,
    ): Int

    fun findByTimetableLectureIdIn(timetableLectureIds: Collection<Long>): List<TimetableLectureReminder>

    fun deleteByTimetableLectureId(timetableLectureId: Long)
}
