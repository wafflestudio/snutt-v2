package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.timetable.model.LectureOverrides
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminderSchedule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

data class DueReminderScheduleRow(
    val scheduleId: Long,
    val reminderId: Long,
    val userId: Long,
    val offsetMinutes: Int,
    val overrides: LectureOverrides?,
    val lectureCourseTitle: String?,
    val lectureCourseTitleEn: String?,
)

interface TimetableLectureReminderScheduleRepository : JpaRepository<TimetableLectureReminderSchedule, Long> {
    fun findByReminderId(reminderId: Long): List<TimetableLectureReminderSchedule>

    @Modifying
    @Query("DELETE FROM TimetableLectureReminderSchedule s WHERE s.reminderId = :reminderId")
    fun deleteByReminderId(reminderId: Long)

    @Query(
        "SELECT new com.wafflestudio.snutt.core.domain.timetable.repository.DueReminderScheduleRow(" +
            "s.id, r.id, t.userId, r.offsetMinutes, tl.overrides, l.courseTitle, l.courseTitleEn) " +
            "FROM TimetableLectureReminderSchedule s " +
            "JOIN TimetableLectureReminder r ON r.id = s.reminderId " +
            "JOIN TimetableLecture tl ON tl.id = r.timetableLectureId " +
            "JOIN Timetable t ON t.id = tl.timetableId " +
            "LEFT JOIN Lecture l ON l.id = tl.lectureId " +
            "WHERE s.day = :day AND s.minute BETWEEN :startMinute AND :endMinute " +
            "AND (s.recentNotifiedAt IS NULL OR s.recentNotifiedAt < :lastNotifiedBefore) " +
            "AND t.isPrimary = true AND t.year = :year AND t.semester = :semester",
    )
    fun findDueSchedules(
        day: DayOfWeek,
        startMinute: Int,
        endMinute: Int,
        lastNotifiedBefore: Instant,
        year: Int,
        semester: Semester,
    ): List<DueReminderScheduleRow>

    @Modifying
    @Query("UPDATE TimetableLectureReminderSchedule s SET s.recentNotifiedAt = :notifiedAt WHERE s.id IN :ids")
    fun markNotified(
        ids: Collection<Long>,
        notifiedAt: Instant,
    )
}
