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
        const val TIME_WINDOW_MINUTES = 10L

        fun fromOffsetMinutes(offsetMinutes: Int?): TimetableLectureReminderOption =
            when (offsetMinutes) {
                null -> NONE
                -10 -> TEN_MINUTES_BEFORE
                0 -> ZERO_MINUTE
                10 -> TEN_MINUTES_AFTER
                else -> throw IllegalArgumentException("Invalid offsetMinutes: $offsetMinutes")
            }
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
    }

    /** 도래한 리마인더를 표시하고 발송 페이로드를 반환한다. 발송 자체는 호출부가 담당한다. */
    @Transactional
    fun processDueReminders(
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
    ): List<DueReminderPush> {
        cleanupPastSemesterReminders(now, current)
        val lastNotifiedBefore = now.toInstant().minus(TIME_WINDOW_MINUTES + 1, ChronoUnit.MINUTES)
        val pushes = mutableListOf<DueReminderPush>()
        dueWindows(now).forEach { window ->
            val reminderIds =
                timetableLectureReminderScheduleRepository.findReminderIdsByFireInRange(
                    DayOfWeek.getOfValue(window.day)!!,
                    window.startMinute,
                    window.endMinute,
                )
            if (reminderIds.isEmpty()) return@forEach
            val reminders = timetableLectureReminderRepository.findAllById(reminderIds.toSet())
            val schedulesByReminderId =
                timetableLectureReminderScheduleRepository.findByReminderIdIn(reminderIds).groupBy { it.reminderId }
            val batch = reminderBatch(reminders, current)
            reminders.forEach { reminder ->
                collect(
                    reminder,
                    schedulesByReminderId[reminder.id].orEmpty(),
                    batch,
                    now,
                    listOf(window),
                    lastNotifiedBefore,
                )?.let { pushes += it }
            }
        }
        return pushes
    }

    private fun reminderBatch(
        reminders: List<TimetableLectureReminder>,
        current: SemesterCalendar.YearSemester,
    ): ReminderBatch {
        val timetableLecturesById =
            timetableLectureRepository.findAllById(reminders.map { it.timetableLectureId }).associateBy { it.id!! }
        // 구 노티파이어와 동일하게 대표 시간표의 리마인더만 보낸다
        val timetablesById =
            timetableRepository
                .findAllById(timetableLecturesById.values.map { it.timetableId })
                .filter { it.isPrimary && it.year == current.year && it.semester == current.semester }
                .associateBy { it.id!! }
        val displaysByTimetableId = timetableService.displaysOf(timetablesById.values.toList())
        return ReminderBatch(timetableLecturesById, timetablesById, displaysByTimetableId)
    }

    private data class DueWindow(
        val day: Int,
        val startMinute: Int,
        val endMinute: Int,
    ) {
        fun contains(schedule: Schedule): Boolean = schedule.day.value == day && schedule.minute in startMinute..endMinute
    }

    private data class ReminderBatch(
        val timetableLecturesById: Map<Long, TimetableLecture>,
        val timetablesById: Map<Long, Timetable>,
        val displaysByTimetableId: Map<Long, List<TimetableLectureDisplay>>,
    )

    private fun dueWindows(now: ZonedDateTime): List<DueWindow> {
        val end = Schedule.fromInstant(now.toInstant())
        val start = end.plusMinutes(-TIME_WINDOW_MINUTES.toInt())
        return if (start.day == end.day) {
            listOf(DueWindow(end.day.value, start.minute, end.minute))
        } else {
            listOf(DueWindow(start.day.value, start.minute, 1439), DueWindow(end.day.value, 0, end.minute))
        }
    }

    private fun cleanupPastSemesterReminders(
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
    ) {
        // 현재 학기보다 이전 학기의 리마인더는 더 이상 울리지 않으므로 정리한다(시간당 1회면 충분)
        val last = lastCleanupAt
        if (last != null && now.toInstant().isBefore(last.plus(Duration.ofHours(1)))) return
        val deleted =
            timetableLectureReminderRepository.deleteByPastSemesters(current.year, current.semester.value)
        if (deleted > 0) log.info("과거 학기 리마인더 정리: {}건", deleted)
        lastCleanupAt = now.toInstant()
    }

    private fun collect(
        reminder: TimetableLectureReminder,
        schedules: List<TimetableLectureReminderSchedule>,
        batch: ReminderBatch,
        now: ZonedDateTime,
        windows: List<DueWindow>,
        lastNotifiedBefore: Instant,
    ): DueReminderPush? {
        val timetableLecture = batch.timetableLecturesById[reminder.timetableLectureId] ?: return null
        val timetable = batch.timetablesById[timetableLecture.timetableId] ?: return null

        // 이번 윈도우에 해당하고 아직 알리지 않은 스케줄만 대상으로 한다(같은 강의의 연속 스케줄 누락 방지)
        val dueSchedules =
            schedules.filter { schedule ->
                val lastNotified = schedule.recentNotifiedAt
                windows.any { it.contains(schedule.toSchedule()) } &&
                    (lastNotified == null || lastNotified.isBefore(lastNotifiedBefore))
            }
        if (dueSchedules.isEmpty()) return null
        val courseTitle =
            batch.displaysByTimetableId[timetable.id]
                ?.firstOrNull { it.id == timetableLecture.id }
                ?.courseTitle ?: return null
        val body =
            when {
                reminder.offsetMinutes == 0 -> "$courseTitle 강의 시간이에요."
                reminder.offsetMinutes > 0 -> "$courseTitle 강의 시작 ${reminder.offsetMinutes}분 후예요."
                else -> "$courseTitle 강의 시작 ${-reminder.offsetMinutes}분 전이에요."
            }

        dueSchedules.forEach { it.recentNotifiedAt = now.toInstant() }
        timetableLectureReminderScheduleRepository.saveAll(dueSchedules)
        return DueReminderPush(userId = timetable.userId, body = body)
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
            option =
                reminder?.let { TimetableLectureReminderOption.fromOffsetMinutes(it.offsetMinutes) } ?: TimetableLectureReminderOption.NONE,
        )
    }

    fun getReminders(
        userId: Long,
        timetableId: Long,
    ): List<TimetableLectureReminderDisplay> = getReminders(timetableService.getTimetable(userId, timetableId))

    private fun getReminders(timetable: Timetable): List<TimetableLectureReminderDisplay> {
        val lectures = timetableLectureRepository.findByTimetableId(timetable.id!!)
        val reminders =
            timetableLectureReminderRepository
                .findByTimetableLectureIdIn(lectures.mapNotNull { it.id })
                .associateBy { it.timetableLectureId }
        val displays = timetableService.displaysOf(listOf(timetable))[timetable.id!!].orEmpty()
        return displays.map { display ->
            TimetableLectureReminderDisplay(
                timetableLectureId = display.id,
                courseTitle = display.courseTitle,
                option =
                    reminders[display.id]?.let {
                        TimetableLectureReminderOption.fromOffsetMinutes(it.offsetMinutes)
                    } ?: TimetableLectureReminderOption.NONE,
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

        if (option == TimetableLectureReminderOption.NONE) {
            timetableLectureReminderRepository.findByTimetableLectureId(timetableLecture.id!!)?.let {
                deleteReminder(it)
            }
            return TimetableLectureReminderDisplay(
                timetableLecture.id!!,
                display.courseTitle,
                TimetableLectureReminderOption.NONE,
            )
        }

        val offsetMinutes = checkNotNull(option.offsetMinutes)
        val schedules = display.classPlaceAndTimes.map { Schedule(it.day, it.startMinute).plusMinutes(offsetMinutes) }
        val reminder =
            timetableLectureReminderRepository.findByTimetableLectureId(timetableLecture.id!!)
                ?: TimetableLectureReminder(
                    timetableLectureId = timetableLecture.id!!,
                    offsetMinutes = offsetMinutes,
                ).also { timetableLectureReminderRepository.save(it) }
        reminder.offsetMinutes = offsetMinutes
        replaceSchedules(reminder.id!!, schedules)
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
        val newSchedules =
            times.map { classTime ->
                Schedule(classTime.day, classTime.startMinute).plusMinutes(reminder.offsetMinutes)
            }
        replaceSchedules(reminder.id!!, newSchedules)
    }

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
                .associateBy { it.day to it.minute }
        timetableLectureReminderScheduleRepository.deleteByReminderId(reminderId)
        timetableLectureReminderScheduleRepository.saveAll(
            schedules.map { schedule ->
                existing[schedule.day to schedule.minute]
                    ?.let { prev -> TimetableLectureReminderSchedule(reminderId, schedule.day, schedule.minute, prev.recentNotifiedAt) }
                    ?: TimetableLectureReminderSchedule(reminderId, schedule.day, schedule.minute)
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
        val display =
            timetableService
                .displaysOf(listOf(timetable))[timetable.id!!]
                .orEmpty()
                .first { it.id == timetableLecture.id }
        return timetableLecture to display
    }

    private fun getTimetableLecture(
        timetable: Timetable,
        timetableLectureId: Long,
    ): TimetableLecture =
        timetableLectureRepository.findByIdAndTimetableId(timetableLectureId, timetable.id!!)
            ?: throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)
}
