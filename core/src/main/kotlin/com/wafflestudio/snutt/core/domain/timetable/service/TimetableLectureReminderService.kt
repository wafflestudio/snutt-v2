package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.common.push.LocalizedPushMessage
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.notification.model.NotificationTemplate
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminderSchedule
import com.wafflestudio.snutt.core.domain.timetable.repository.DueReminderScheduleRow
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRow
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderScheduleRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

enum class TimetableLectureReminderOption(
    val offsetMinutes: Int?,
) {
    NONE(null),
    TEN_MINUTES_BEFORE(-10),
    ZERO_MINUTE(0),
    TEN_MINUTES_AFTER(10),
    ;

    companion object {
        fun fromOffsetMinutes(offsetMinutes: Int?): TimetableLectureReminderOption = entries.first { it.offsetMinutes == offsetMinutes }
    }
}

data class TimetableLectureReminderDisplay(
    val timetableLectureId: Long,
    val courseTitle: String,
    val option: TimetableLectureReminderOption,
)

data class DueReminderPush(
    val userId: Long,
    val message: LocalizedPushMessage,
)

@Service
class TimetableLectureReminderService(
    private val timetableService: TimetableService,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableLectureReminderRepository: TimetableLectureReminderRepository,
    private val timetableLectureReminderScheduleRepository: TimetableLectureReminderScheduleRepository,
    private val lectureService: LectureService,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    private companion object {
        const val TIME_WINDOW_MINUTES = 10L
        const val LAST_MINUTE_OF_DAY = 1439
        const val CLEANUP_BATCH_SIZE = 500
    }

    private data class DueWindow(
        val day: DayOfWeek,
        val startMinute: Int,
        val endMinute: Int,
    )

    @Transactional
    fun processDueReminders(
        now: ZonedDateTime,
        current: YearAndSemester,
    ): List<DueReminderPush> {
        val lastNotifiedBefore = now.toInstant().minus(TIME_WINDOW_MINUTES + 1, ChronoUnit.MINUTES)
        return dueWindows(now).flatMap { window -> processWindow(window, now.toInstant(), current, lastNotifiedBefore) }
    }

    private fun processWindow(
        window: DueWindow,
        notifiedAt: Instant,
        current: YearAndSemester,
        lastNotifiedBefore: Instant,
    ): List<DueReminderPush> {
        val rows =
            timetableLectureReminderScheduleRepository.findDueSchedules(
                day = window.day,
                startMinute = window.startMinute,
                endMinute = window.endMinute,
                lastNotifiedBefore = lastNotifiedBefore,
                year = current.year,
                semester = current.semester,
            )
        if (rows.isEmpty()) return emptyList()
        timetableLectureReminderScheduleRepository.markNotified(rows.map { it.scheduleId }, notifiedAt)
        return rows.distinctBy { it.reminderId }.map { row ->
            DueReminderPush(
                userId = row.userId,
                message = reminderMessage(row.localizedCourseTitle(), row.offsetMinutes),
            )
        }
    }

    private fun DueReminderScheduleRow.localizedCourseTitle(): LocalizedMessage =
        overrides?.courseTitle?.let(LocalizedMessage::same)
            ?: lectureCourseTitle?.let { LocalizedMessage(ko = it, en = lectureCourseTitleEn ?: it) }
            ?: LocalizedMessage.same("")

    private fun reminderMessage(
        courseTitle: LocalizedMessage,
        offsetMinutes: Int,
    ): LocalizedPushMessage =
        when {
            offsetMinutes == 0 -> NotificationTemplate.CLASS_REMINDER_NOW.render(courseTitle)
            offsetMinutes > 0 -> NotificationTemplate.CLASS_REMINDER_AFTER.render(courseTitle, offsetMinutes)
            else -> NotificationTemplate.CLASS_REMINDER_BEFORE.render(courseTitle, -offsetMinutes)
        }

    private fun dueWindows(now: ZonedDateTime): List<DueWindow> {
        val end = Schedule.fromInstant(now.toInstant())
        val start = end.plusMinutes(-TIME_WINDOW_MINUTES.toInt())
        return if (start.day == end.day) {
            listOf(DueWindow(end.day, start.minute, end.minute))
        } else {
            listOf(DueWindow(start.day, start.minute, LAST_MINUTE_OF_DAY), DueWindow(end.day, 0, end.minute))
        }
    }

    /**
     * 지난 학기 리마인더를 [CLEANUP_BATCH_SIZE]개씩 지운다. 지울 id는 락 없는 SELECT로 고르고, 묶음마다 그 시간표 행을 id 순으로 잠근 뒤
     * 리마인더만 id로 지운다. 사용자 요청과 같은 순서(timetable → 리마인더)로 잠그므로 데드락이 나지 않고, 리마인더 설정과 겹쳐도
     * 지워진 리마인더에 발송 시각을 넣지 않는다.
     */
    fun deletePastSemesterReminders(current: YearAndSemester) {
        var deleted = 0
        var afterId = 0L
        while (true) {
            val batch =
                timetableLectureReminderRepository.findPastSemesterReminders(
                    current.year,
                    current.semester.value,
                    afterId,
                    CLEANUP_BATCH_SIZE,
                )
            if (batch.isEmpty()) break
            transactionTemplate.executeWithoutResult {
                timetableRepository.findAllForUpdateByIdIn(batch.map { it.timetableId }.distinct())
                timetableLectureReminderRepository.deleteAllByIdInBatch(batch.map { it.reminderId })
            }
            deleted += batch.size
            afterId = batch.last().reminderId
            if (batch.size < CLEANUP_BATCH_SIZE) break
        }
        if (deleted > 0) log.info("과거 학기 리마인더 정리: {}건", deleted)
    }

    fun getReminder(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
    ): TimetableLectureReminderDisplay {
        val timetable = timetableService.getTimetable(userId, timetableId)
        val row =
            timetableLectureRepository.findReminderRowByTimetableIdAndId(timetable.id!!, timetableLectureId)
                ?: throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)
        return row.toDisplay()
    }

    fun getReminders(
        userId: Long,
        timetableId: Long,
    ): List<TimetableLectureReminderDisplay> {
        val timetable = timetableService.getTimetable(userId, timetableId)
        return timetableLectureRepository.findReminderRowsByTimetableId(timetable.id!!).map { it.toDisplay() }
    }

    @Transactional
    fun modifyReminder(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
        option: TimetableLectureReminderOption,
    ): TimetableLectureReminderDisplay {
        val timetable = timetableService.getTimetable(userId, timetableId)
        val row =
            timetableLectureRepository.findReminderRowByTimetableIdAndId(timetable.id!!, timetableLectureId)
                ?: throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)
        val classPlaceAndTimes =
            row.overrides?.classPlaceAndTimes
                ?: row.lectureId?.let { lectureService.classTimesByLectureId(listOf(it))[it] }
                ?: emptyList()
        if (classPlaceAndTimes.isEmpty()) throw SnuttException(ErrorType.TIMETABLE_LECTURE_REMINDER_INVALID_TIME)
        val existing = timetableLectureReminderRepository.findByTimetableLectureId(row.timetableLectureId)
        val offsetMinutes = option.offsetMinutes

        if (offsetMinutes == null) {
            existing?.let(timetableLectureReminderRepository::delete)
        } else {
            val reminder =
                existing ?: timetableLectureReminderRepository.save(TimetableLectureReminder(row.timetableLectureId, offsetMinutes))
            reminder.offsetMinutes = offsetMinutes
            replaceSchedules(reminder.id!!, schedulesOf(classPlaceAndTimes, offsetMinutes))
        }
        return TimetableLectureReminderDisplay(row.timetableLectureId, row.courseTitle(), option)
    }

    @Transactional
    fun recomputeForTimetableLecture(
        timetableLectureId: Long,
        times: List<ClassPlaceAndTime>,
    ) {
        val reminder = timetableLectureReminderRepository.findByTimetableLectureId(timetableLectureId) ?: return
        if (times.isEmpty()) {
            timetableLectureReminderRepository.delete(reminder)
            return
        }
        replaceSchedules(reminder.id!!, schedulesOf(times, reminder.offsetMinutes))
    }

    private fun schedulesOf(
        times: List<ClassPlaceAndTime>,
        offsetMinutes: Int,
    ): List<Schedule> = times.map { Schedule(it.day, it.startMinute).plusMinutes(offsetMinutes) }.distinct()

    /**
     * 발송 시각을 [schedules]로 맞춘다. 그대로인 발송 시각은 건드리지 않고(최근 발송 시각도 남는다) 빠진 것만 id로 지우고 새것만 넣는다.
     * 예전처럼 reminder_id 범위로 DELETE한 뒤 INSERT하면 idx_reminder_schedule_fire에 gap 락이 잡혀, 인덱스에서 이웃한 다른 리마인더의
     * INSERT와 insert intention 데드락이 났다. 같은 리마인더를 고치는 요청은 시간표 잠금으로 줄을 선다.
     */
    private fun replaceSchedules(
        reminderId: Long,
        schedules: List<Schedule>,
    ) {
        val existing = timetableLectureReminderScheduleRepository.findByReminderId(reminderId)
        val stale = existing.filter { it.toSchedule() !in schedules }
        if (stale.isNotEmpty()) timetableLectureReminderScheduleRepository.deleteAllByIdInBatch(stale.map { it.id!! })
        val kept = existing.map { it.toSchedule() }.toSet()
        timetableLectureReminderScheduleRepository.saveAll(
            schedules.filter { it !in kept }.map { schedule ->
                TimetableLectureReminderSchedule(reminderId, schedule.day, schedule.minute, null)
            },
        )
    }

    private fun TimetableLectureReminderRow.courseTitle(): String = overrides?.courseTitle ?: lectureCourseTitle ?: ""

    private fun TimetableLectureReminderRow.toDisplay() =
        TimetableLectureReminderDisplay(
            timetableLectureId = timetableLectureId,
            courseTitle = courseTitle(),
            option = TimetableLectureReminderOption.fromOffsetMinutes(offsetMinutes),
        )
}
