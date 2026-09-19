package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.util.SemesterCalendar
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminderSchedule
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderScheduleRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
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
    val body: String,
)

@Service
class TimetableLectureReminderService(
    private val timetableService: TimetableService,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableLectureReminderRepository: TimetableLectureReminderRepository,
    private val timetableLectureReminderScheduleRepository: TimetableLectureReminderScheduleRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var lastCleanupAt: Instant? = null

    private companion object {
        const val TIME_WINDOW_MINUTES = 10L
        const val LAST_MINUTE_OF_DAY = 1439
    }

    private data class DueWindow(
        val day: DayOfWeek,
        val startMinute: Int,
        val endMinute: Int,
    ) {
        fun contains(schedule: Schedule): Boolean = schedule.day == day && schedule.minute in startMinute..endMinute
    }

    @Transactional
    fun processDueReminders(
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
    ): List<DueReminderPush> {
        cleanupPastSemesterReminders(now, current)
        val lastNotifiedBefore = now.toInstant().minus(TIME_WINDOW_MINUTES + 1, ChronoUnit.MINUTES)
        return dueWindows(now).flatMap { window -> processWindow(window, now, current, lastNotifiedBefore) }
    }

    private fun processWindow(
        window: DueWindow,
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
        lastNotifiedBefore: Instant,
    ): List<DueReminderPush> {
        val reminderIds =
            timetableLectureReminderScheduleRepository.findReminderIdsByFireInRange(window.day, window.startMinute, window.endMinute)
        if (reminderIds.isEmpty()) return emptyList()
        val reminders = timetableLectureReminderRepository.findAllById(reminderIds.toSet())
        val schedulesByReminderId =
            timetableLectureReminderScheduleRepository.findByReminderIdIn(reminderIds).groupBy { it.reminderId }
        val timetableLecturesById =
            timetableLectureRepository.findAllById(reminders.map { it.timetableLectureId }).associateBy { it.id!! }
        val timetablesById =
            timetableRepository
                .findAllById(timetableLecturesById.values.map { it.timetableId })
                .filter { it.isPrimary && it.year == current.year && it.semester == current.semester }
                .associateBy { it.id!! }
        val displaysByTimetableId = timetableService.displaysOf(timetablesById.values.toList())

        return reminders.mapNotNull { reminder ->
            val timetableLecture = timetableLecturesById[reminder.timetableLectureId] ?: return@mapNotNull null
            val timetable = timetablesById[timetableLecture.timetableId] ?: return@mapNotNull null
            val dueSchedules =
                schedulesByReminderId[reminder.id].orEmpty().filter { schedule ->
                    window.contains(schedule.toSchedule()) &&
                        (schedule.recentNotifiedAt?.isBefore(lastNotifiedBefore) ?: true)
                }
            if (dueSchedules.isEmpty()) return@mapNotNull null
            val courseTitle =
                displaysByTimetableId
                    .getValue(timetable.id!!)
                    .lectures
                    .firstOrNull { it.id == timetableLecture.id }
                    ?.courseTitle ?: return@mapNotNull null
            dueSchedules.forEach { it.recentNotifiedAt = now.toInstant() }
            timetableLectureReminderScheduleRepository.saveAll(dueSchedules)
            DueReminderPush(userId = timetable.userId, body = reminderBody(courseTitle, reminder.offsetMinutes))
        }
    }

    private fun reminderBody(
        courseTitle: String,
        offsetMinutes: Int,
    ): String =
        when {
            offsetMinutes == 0 -> "$courseTitle 강의 시간이에요."
            offsetMinutes > 0 -> "$courseTitle 강의 시작 ${offsetMinutes}분 후예요."
            else -> "$courseTitle 강의 시작 ${-offsetMinutes}분 전이에요."
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

    private fun cleanupPastSemesterReminders(
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
    ) {
        val last = lastCleanupAt
        if (last != null && now.toInstant().isBefore(last.plus(Duration.ofHours(1)))) return
        val deleted = timetableLectureReminderRepository.deleteByPastSemesters(current.year, current.semester.value)
        if (deleted > 0) log.info("과거 학기 리마인더 정리: {}건", deleted)
        lastCleanupAt = now.toInstant()
    }

    fun getReminder(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
    ): TimetableLectureReminderDisplay {
        val (timetableLecture, display) = getTimetableLectureWithDisplay(userId, timetableId, timetableLectureId)
        val reminder = timetableLectureReminderRepository.findByTimetableLectureId(timetableLecture.id!!)
        return TimetableLectureReminderDisplay(
            timetableLectureId = timetableLecture.id!!,
            courseTitle = display.courseTitle,
            option = TimetableLectureReminderOption.fromOffsetMinutes(reminder?.offsetMinutes),
        )
    }

    fun getReminders(
        userId: Long,
        timetableId: Long,
    ): List<TimetableLectureReminderDisplay> {
        val timetable = timetableService.getTimetable(userId, timetableId)
        val lectures = timetableLectureRepository.findByTimetableId(timetable.id!!)
        val reminders =
            timetableLectureReminderRepository
                .findByTimetableLectureIdIn(lectures.map { it.id!! })
                .associateBy { it.timetableLectureId }
        return timetableService.displayOf(timetable).lectures.map { display ->
            TimetableLectureReminderDisplay(
                timetableLectureId = display.id,
                courseTitle = display.courseTitle,
                option = TimetableLectureReminderOption.fromOffsetMinutes(reminders[display.id]?.offsetMinutes),
            )
        }
    }

    @Transactional
    fun modifyReminder(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
        option: TimetableLectureReminderOption,
    ): TimetableLectureReminderDisplay {
        val (timetableLecture, display) = getTimetableLectureWithDisplay(userId, timetableId, timetableLectureId)
        if (display.classPlaceAndTimes.isEmpty()) throw SnuttException(ErrorType.TIMETABLE_LECTURE_REMINDER_INVALID_TIME)
        val existing = timetableLectureReminderRepository.findByTimetableLectureId(timetableLecture.id!!)
        val offsetMinutes = option.offsetMinutes

        if (offsetMinutes == null) {
            existing?.let { deleteReminder(it) }
        } else {
            val reminder =
                existing ?: timetableLectureReminderRepository.save(TimetableLectureReminder(timetableLecture.id!!, offsetMinutes))
            reminder.offsetMinutes = offsetMinutes
            replaceSchedules(reminder.id!!, schedulesOf(display.classPlaceAndTimes, offsetMinutes))
        }
        return TimetableLectureReminderDisplay(timetableLecture.id!!, display.courseTitle, option)
    }

    @Transactional
    fun recomputeForTimetableLecture(
        timetableLectureId: Long,
        times: List<ClassPlaceAndTime>,
    ) {
        val reminder = timetableLectureReminderRepository.findByTimetableLectureId(timetableLectureId) ?: return
        if (times.isEmpty()) {
            deleteReminder(reminder)
            return
        }
        replaceSchedules(reminder.id!!, schedulesOf(times, reminder.offsetMinutes))
    }

    private fun schedulesOf(
        times: List<ClassPlaceAndTime>,
        offsetMinutes: Int,
    ): List<Schedule> = times.map { Schedule(it.day, it.startMinute).plusMinutes(offsetMinutes) }

    private fun deleteReminder(reminder: TimetableLectureReminder) {
        timetableLectureReminderScheduleRepository.deleteByReminderId(reminder.id!!)
        timetableLectureReminderRepository.delete(reminder)
    }

    private fun replaceSchedules(
        reminderId: Long,
        schedules: List<Schedule>,
    ) {
        val existing =
            timetableLectureReminderScheduleRepository
                .findByReminderId(reminderId)
                .associateBy { it.toSchedule() }
        timetableLectureReminderScheduleRepository.deleteByReminderId(reminderId)
        timetableLectureReminderScheduleRepository.saveAll(
            schedules.map { schedule ->
                TimetableLectureReminderSchedule(reminderId, schedule.day, schedule.minute, existing[schedule]?.recentNotifiedAt)
            },
        )
    }

    private fun getTimetableLectureWithDisplay(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
    ): Pair<TimetableLecture, TimetableLectureDisplay> {
        val timetable = timetableService.getTimetable(userId, timetableId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureId)
        val display = timetableService.displayOf(timetable).lectures.first { it.id == timetableLecture.id }
        return timetableLecture to display
    }

    private fun getTimetableLecture(
        timetable: Timetable,
        timetableLectureId: Long,
    ): TimetableLecture =
        timetableLectureRepository.findByIdAndTimetableId(timetableLectureId, timetable.id!!)
            ?: throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)
}
