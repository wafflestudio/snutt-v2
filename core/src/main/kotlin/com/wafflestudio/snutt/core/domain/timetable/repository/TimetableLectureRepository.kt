package com.wafflestudio.snutt.core.domain.timetable.repository

import com.wafflestudio.snutt.core.domain.timetable.model.LectureOverrides
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

data class TimetableLectureReminderRow(
    val timetableLectureId: Long,
    val lectureId: Long?,
    val overrides: LectureOverrides?,
    val lectureCourseTitle: String?,
    val offsetMinutes: Int?,
)

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

    fun deleteByTimetableIdAndId(
        timetableId: Long,
        id: Long,
    )

    @Modifying
    @Query(
        value =
            "INSERT INTO timetable_lecture (timetable_id, lecture_id, custom_color, palette_index, overrides, created_at, updated_at) " +
                "SELECT :targetTimetableId, lecture_id, custom_color, palette_index, overrides, :now, :now " +
                "FROM timetable_lecture WHERE timetable_id = :sourceTimetableId",
        nativeQuery = true,
    )
    fun copyByTimetableId(
        sourceTimetableId: Long,
        targetTimetableId: Long,
        now: Instant,
    )

    @Query(
        "SELECT new com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRow(" +
            "tl.id, tl.lectureId, tl.overrides, l.courseTitle, r.offsetMinutes) " +
            "FROM TimetableLecture tl " +
            "LEFT JOIN Lecture l ON l.id = tl.lectureId " +
            "LEFT JOIN TimetableLectureReminder r ON r.timetableLectureId = tl.id " +
            "WHERE tl.timetableId = :timetableId",
    )
    fun findReminderRowsByTimetableId(timetableId: Long): List<TimetableLectureReminderRow>

    @Query(
        "SELECT new com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRow(" +
            "tl.id, tl.lectureId, tl.overrides, l.courseTitle, r.offsetMinutes) " +
            "FROM TimetableLecture tl " +
            "LEFT JOIN Lecture l ON l.id = tl.lectureId " +
            "LEFT JOIN TimetableLectureReminder r ON r.timetableLectureId = tl.id " +
            "WHERE tl.timetableId = :timetableId AND tl.id = :timetableLectureId",
    )
    fun findReminderRowByTimetableIdAndId(
        timetableId: Long,
        timetableLectureId: Long,
    ): TimetableLectureReminderRow?
}
