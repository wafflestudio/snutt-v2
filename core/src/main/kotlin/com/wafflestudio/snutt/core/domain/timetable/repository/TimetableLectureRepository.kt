package com.wafflestudio.snutt.core.domain.timetable.repository

import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.timetable.model.LectureOverrides
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

data class TimetableLectureReminderRow(
    val timetableLectureId: Long,
    val lectureId: Long?,
    val overrides: LectureOverrides?,
    val lectureCourseTitle: String?,
    val offsetMinutes: Int?,
)

interface TimetableLectureRepository :
    JpaRepository<TimetableLecture, Long>,
    KotlinJdslJpqlExecutor {
    fun findByTimetableId(timetableId: Long): List<TimetableLecture>

    fun findByIdAndTimetableId(
        id: Long,
        timetableId: Long,
    ): TimetableLecture?

    fun findByTimetableIdIn(timetableIds: Collection<Long>): List<TimetableLecture>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByTimetableIdIn(timetableIds: Collection<Long>): List<TimetableLecture>

    fun findByLectureIdIn(lectureIds: Collection<Long>): List<TimetableLecture>

    fun findReminderRowsByTimetableId(timetableId: Long): List<TimetableLectureReminderRow> = findReminderRows(timetableId, null)

    fun findReminderRowByTimetableIdAndId(
        timetableId: Long,
        timetableLectureId: Long,
    ): TimetableLectureReminderRow? = findReminderRows(timetableId, timetableLectureId).singleOrNull()

    private fun findReminderRows(
        timetableId: Long,
        timetableLectureId: Long?,
    ): List<TimetableLectureReminderRow> =
        findAll {
            jpql {
                selectNew<TimetableLectureReminderRow>(
                    path(TimetableLecture::id),
                    path(TimetableLecture::lectureId),
                    path(TimetableLecture::overrides),
                    path(Lecture::courseTitle),
                    path(TimetableLectureReminder::offsetMinutes),
                ).from(
                    entity(TimetableLecture::class),
                    leftJoin(Lecture::class).on(path(Lecture::id).equal(path(TimetableLecture::lectureId))),
                    leftJoin(TimetableLectureReminder::class).on(
                        path(TimetableLectureReminder::timetableLectureId).equal(path(TimetableLecture::id)),
                    ),
                ).whereAnd(
                    path(TimetableLecture::timetableId).equal(timetableId),
                    timetableLectureId?.let { path(TimetableLecture::id).equal(it) },
                )
            }
        }.filterNotNull()
}
