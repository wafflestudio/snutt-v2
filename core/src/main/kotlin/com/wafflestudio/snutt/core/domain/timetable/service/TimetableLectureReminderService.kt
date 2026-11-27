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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableLectureReminderRepository: TimetableLectureReminderRepository,
    private val timetableLectureReminderScheduleRepository: TimetableLectureReminderScheduleRepository,
    private val lectureService: LectureService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val TIME_WINDOW_MINUTES = 10L
        const val LAST_MINUTE_OF_DAY = 1439
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

    fun deletePastSemesterReminders(current: YearAndSemester) {
        val deleted = timetableLectureReminderRepository.deleteByPastSemesters(current.year, current.semester.value)
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
        val notifiedAtBySchedule =
            timetableLectureReminderScheduleRepository
                .findByReminderId(reminder.id!!)
                .mapNotNull { schedule -> schedule.recentNotifiedAt?.let { schedule.toSchedule() to it } }
                .toMap()
        replaceSchedules(reminder.id!!, schedulesOf(times, reminder.offsetMinutes), notifiedAtBySchedule)
    }

    private fun schedulesOf(
        times: List<ClassPlaceAndTime>,
        offsetMinutes: Int,
    ): List<Schedule> = times.map { Schedule(it.day, it.startMinute).plusMinutes(offsetMinutes) }.distinct()

    private fun replaceSchedules(
        reminderId: Long,
        schedules: List<Schedule>,
        notifiedAtBySchedule: Map<Schedule, Instant> = emptyMap(),
    ) {
        timetableLectureReminderScheduleRepository.deleteByReminderId(reminderId)
        timetableLectureReminderScheduleRepository.saveAll(
            schedules.map { schedule ->
                TimetableLectureReminderSchedule(reminderId, schedule.day, schedule.minute, notifiedAtBySchedule[schedule])
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
