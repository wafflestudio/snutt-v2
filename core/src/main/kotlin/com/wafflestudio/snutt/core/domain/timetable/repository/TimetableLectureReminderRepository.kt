package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface PastSemesterReminder {
    val reminderId: Long
    val timetableId: Long
}

interface TimetableLectureReminderRepository : JpaRepository<TimetableLectureReminder, Long> {
    fun findByTimetableLectureId(timetableLectureId: Long): TimetableLectureReminder?

    /**
     * 지난 학기 시간표의 리마인더를 id 순으로 [limit]개 고른다(일반 SELECT라 락을 잡지 않는다). 지우는 쪽은 시간표 행을 먼저 잠근다.
     * 예전의 DELETE JOIN은 리마인더가 달린 모든 timetable_lecture·timetable 행을 S락으로 읽어 강의 수정(timetable → timetable_lecture)과 데드락이 났다.
     */
    @Query(
        value =
            "SELECT r.id AS reminderId, tl.timetable_id AS timetableId FROM timetable_lecture_reminder r " +
                "JOIN timetable_lecture tl ON tl.id = r.timetable_lecture_id " +
                "JOIN timetable t ON t.id = tl.timetable_id " +
                "WHERE r.id > :afterId AND (t.year < :year OR (t.year = :year AND t.semester < :semesterValue)) " +
                "ORDER BY r.id LIMIT :limit",
        nativeQuery = true,
    )
    fun findPastSemesterReminders(
        year: Int,
        semesterValue: Int,
        afterId: Long,
        limit: Int,
    ): List<PastSemesterReminder>
}
