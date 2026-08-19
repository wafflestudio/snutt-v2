package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class TimetableLectureReminderOption(
    val offsetMinutes: Int?,
) {
    NONE(null),
    TEN_MINUTES_BEFORE(-10),
    ZERO_MINUTE(0),
    TEN_MINUTES_AFTER(10),
    ;

    companion object {
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

@Service
class TimetableLectureReminderService(
    private val timetableService: TimetableService,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableLectureReminderRepository: TimetableLectureReminderRepository,
) {
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
            timetableLectureReminderRepository.deleteByTimetableLectureId(timetableLecture.id!!)
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
                    scheduleList = schedules,
                ).also { timetableLectureReminderRepository.save(it) }
        reminder.offsetMinutes = offsetMinutes
        reminder.scheduleList = schedules
        reminder.recomputeNextFire()
        return TimetableLectureReminderDisplay(timetableLecture.id!!, display.courseTitle, option)
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
        val newSchedules =
            times.map { classTime ->
                val newSchedule = Schedule(classTime.day, classTime.startMinute).plusMinutes(reminder.offsetMinutes)
                reminder.scheduleList.firstOrNull { it.day == newSchedule.day && it.minute == newSchedule.minute } ?: newSchedule
            }
        if (newSchedules == reminder.scheduleList) return
        reminder.scheduleList = newSchedules
        reminder.recomputeNextFire()
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

    fun getReminder(
        userId: Long,
        timetableExternalId: String,
        timetableLectureExternalId: String,
    ): TimetableLectureReminderDisplay = getReminder(userId, timetableExternalId.toLong(), timetableLectureExternalId.toLong())

    fun getReminders(
        userId: Long,
        timetableExternalId: String,
    ): List<TimetableLectureReminderDisplay> = getReminders(userId, timetableExternalId.toLong())

    @Transactional
    fun modifyReminder(
        userId: Long,
        timetableExternalId: String,
        timetableLectureExternalId: String,
        option: TimetableLectureReminderOption,
    ): TimetableLectureReminderDisplay = modifyReminder(userId, timetableExternalId.toLong(), timetableLectureExternalId.toLong(), option)
}
