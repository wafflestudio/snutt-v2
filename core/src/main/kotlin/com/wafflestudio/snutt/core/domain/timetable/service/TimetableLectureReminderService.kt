package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
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
    val timetableLectureId: String,
    val courseTitle: String,
    val option: TimetableLectureReminderOption,
)

// v1과 달리 모든 조회가 userId로 시간표 소유권을 검증한다 (PLAN.md §4: v1 소유권 검증 누락 수정)
@Service
class TimetableLectureReminderService(
    private val timetableService: TimetableService,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableLectureReminderRepository: TimetableLectureReminderRepository,
) {
    fun getReminder(
        userId: Long,
        timetableExternalId: String,
        timetableLectureExternalId: String,
    ): TimetableLectureReminderDisplay {
        val (timetableLecture, display) = getTimetableLectureWithDisplay(userId, timetableExternalId, timetableLectureExternalId)
        val reminder = timetableLectureReminderRepository.findByTimetableLectureId(timetableLecture.id!!)
        return TimetableLectureReminderDisplay(
            timetableLectureId = timetableLecture.externalId,
            courseTitle = display.courseTitle,
            option =
                reminder?.let { TimetableLectureReminderOption.fromOffsetMinutes(it.offsetMinutes) } ?: TimetableLectureReminderOption.NONE,
        )
    }

    fun getReminders(
        userId: Long,
        timetableExternalId: String,
    ): List<TimetableLectureReminderDisplay> {
        val timetable = timetableService.getTimetable(userId, timetableExternalId)
        val lectures = timetableLectureRepository.findByTimetableId(timetable.id!!)
        val idByExternalId = lectures.associateBy { it.externalId }
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
                    reminders[idByExternalId[display.id]?.id]?.let {
                        TimetableLectureReminderOption.fromOffsetMinutes(it.offsetMinutes)
                    } ?: TimetableLectureReminderOption.NONE,
            )
        }
    }

    @Transactional
    fun modifyReminder(
        userId: Long,
        timetableExternalId: String,
        timetableLectureExternalId: String,
        option: TimetableLectureReminderOption,
    ): TimetableLectureReminderDisplay {
        val (timetableLecture, display) = getTimetableLectureWithDisplay(userId, timetableExternalId, timetableLectureExternalId)
        if (display.classPlaceAndTime.isEmpty()) throw SnuttException(ErrorType.TIMETABLE_LECTURE_REMINDER_INVALID_TIME)

        if (option == TimetableLectureReminderOption.NONE) {
            timetableLectureReminderRepository.deleteByTimetableLectureId(timetableLecture.id!!)
            return TimetableLectureReminderDisplay(timetableLecture.externalId, display.courseTitle, TimetableLectureReminderOption.NONE)
        }

        val offsetMinutes = checkNotNull(option.offsetMinutes)
        val schedules = display.classPlaceAndTime.map { Schedule(it.day, it.startMinute).plusMinutes(offsetMinutes) }
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
        return TimetableLectureReminderDisplay(timetableLecture.externalId, display.courseTitle, option)
    }

    // 강의 시간이 바뀌면 스케줄을 다시 계산한다. 시간이 비면 리마인더를 삭제한다 (v1 이벤트 리스너 이식)
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
                // 이미 알림을 보낸 schedule의 recentNotifiedAt은 유지한다
                reminder.scheduleList.firstOrNull { it.day == newSchedule.day && it.minute == newSchedule.minute } ?: newSchedule
            }
        if (newSchedules == reminder.scheduleList) return
        reminder.scheduleList = newSchedules
        reminder.recomputeNextFire()
    }

    private fun getTimetableLectureWithDisplay(
        userId: Long,
        timetableExternalId: String,
        timetableLectureExternalId: String,
    ): Pair<TimetableLecture, TimetableLectureDisplay> {
        val timetable = timetableService.getTimetable(userId, timetableExternalId)
        val timetableLecture =
            timetableLectureRepository.findByTimetableIdAndExternalId(timetable.id!!, timetableLectureExternalId)
                ?: throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)
        val display =
            timetableService
                .displaysOf(listOf(timetable))[timetable.id!!]
                .orEmpty()
                .first { it.id == timetableLectureExternalId }
        return timetableLecture to display
    }
}
