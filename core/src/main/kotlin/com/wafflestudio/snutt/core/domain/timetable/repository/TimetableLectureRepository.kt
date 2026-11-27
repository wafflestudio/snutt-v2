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
import org.springframework.data.jpa.repository.Query

data class TimetableLectureReminderRow(
    val timetableLectureId: Long,
    val lectureId: Long?,
    val overrides: LectureOverrides?,
    val lectureCourseTitle: String?,
    val offsetMinutes: Int?,
)

data class LectureTimetableId(
    val lectureId: Long?,
    val timetableId: Long,
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

    @Query("SELECT DISTINCT tl.timetableId FROM TimetableLecture tl WHERE tl.lectureId IN :lectureIds")
    fun findTimetableIdsByLectureIdIn(lectureIds: Collection<Long>): List<Long>

    /** 강의마다 그 강의를 담은 시간표. 한 시간표에 같은 강의가 두 번 담기지 않으므로 DISTINCT 없이 읽는다(임시 테이블을 피한다). */
    @Query(
        "SELECT new com.wafflestudio.snutt.core.domain.timetable.repository.LectureTimetableId(tl.lectureId, tl.timetableId) " +
            "FROM TimetableLecture tl WHERE tl.lectureId IN :lectureIds",
    )
    fun findLectureTimetableIdsByLectureIdIn(lectureIds: Collection<Long>): List<LectureTimetableId>

    /** 이 강의들을 담은 행을 공유 락으로 읽는다(잠금 읽기라 트랜잭션 읽기 뷰와 상관없이 커밋된 최신 행을 본다). */
    @Query(
        value = "SELECT DISTINCT lecture_id FROM timetable_lecture WHERE lecture_id IN (:lectureIds) FOR SHARE",
        nativeQuery = true,
    )
    fun lockReferencedLectureIds(lectureIds: Collection<Long>): List<Long>

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
