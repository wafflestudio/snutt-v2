package com.wafflestudio.snutt.api.scheduler

import com.wafflestudio.snutt.core.common.util.SchedulerLock
import com.wafflestudio.snutt.core.common.util.SemesterCalendar
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.notification.service.TargetedPush
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Component
class ReminderScheduler(
    private val timetableLectureReminderRepository: TimetableLectureReminderRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableRepository: TimetableRepository,
    private val timetableService: TimetableService,
    private val pushService: PushService,
    private val schedulerLock: SchedulerLock,
) {
    companion object {
        private const val TIME_WINDOW_MINUTES = 10L
        private val KST = ZoneId.of("Asia/Seoul")
    }

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 * * * * *")
    fun fireDueReminders() {
        schedulerLock.withLock("reminder", Duration.ofSeconds(50)) {
            val current = SemesterCalendar.current()
            if (current == null) {
                log.debug("현재 진행 중인 학기가 없어 리마인더를 보내지 않는다")
                return@withLock
            }
            fireDueReminders(ZonedDateTime.now(KST), current)
        }
    }

    fun fireDueReminders(
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
    ) {
        findDueReminders(now.toInstant()).forEach { reminder -> fire(reminder, now, current) }
    }

    private fun findDueReminders(now: Instant): List<TimetableLectureReminder> {
        val end = Schedule.fromInstant(now)
        val start = end.plusMinutes(-TIME_WINDOW_MINUTES.toInt())
        val lastNotifiedBefore = now.minus(TIME_WINDOW_MINUTES + 1, ChronoUnit.MINUTES)
        return if (start.day == end.day) {
            timetableLectureReminderRepository.findDueRemindersInTimeRange(end.day.value, start.minute, end.minute, lastNotifiedBefore)
        } else {
            timetableLectureReminderRepository.findDueRemindersInTimeRange(start.day.value, start.minute, 1439, lastNotifiedBefore) +
                timetableLectureReminderRepository.findDueRemindersInTimeRange(end.day.value, 0, end.minute, lastNotifiedBefore)
        }
    }

    private fun fire(
        reminder: TimetableLectureReminder,
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
    ) {
        val timetableLecture =
            timetableLectureRepository.findByIdOrNull(reminder.timetableLectureId) ?: return
        val timetable =
            timetableRepository.findByIdOrNull(timetableLecture.timetableId) ?: return
        if (timetable.year != current.year || timetable.semester != current.semester) return
        val courseTitle =
            timetableService
                .displaysOf(listOf(timetable))[timetable.id]
                ?.firstOrNull { it.id == timetableLecture.externalId }
                ?.courseTitle ?: return
        val body =
            when {
                reminder.offsetMinutes == 0 -> "$courseTitle 강의 시간이에요."
                reminder.offsetMinutes > 0 -> "$courseTitle 강의 시작 ${reminder.offsetMinutes}분 후예요."
                else -> "$courseTitle 강의 시작 ${-reminder.offsetMinutes}분 전이에요."
            }
        pushService.sendPushAndNotification(
            userIds = listOf(timetable.userId),
            title = "📚 강의 리마인더",
            body = body,
            type = NotificationType.NORMAL,
            preferenceType = PushPreferenceType.NORMAL,
            urlScheme = "snutt://timetable",
        )
        reminder.recentNotifiedAt = now.toInstant()
        reminder.recomputeNextFire(now.toInstant().plusSeconds(60))
        timetableLectureReminderRepository.save(reminder)
    }
}

@Component
class DiaryScheduler(
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val lectureRepository: LectureRepository,
    private val coursebookService: CoursebookService,
    private val pushService: PushService,
    private val schedulerLock: SchedulerLock,
    @param:Value("\${snutt.diary.push-sample-rate:1.0}") private val sampleRate: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 19 * * MON,WED,FRI", zone = "Asia/Seoul")
    fun sendDiaryNotificationsScheduled() {
        schedulerLock.withLock("diary", Duration.ofMinutes(10)) {
            if (SemesterCalendar.current() == null) {
                log.debug("현재 진행 중인 학기가 없어 일기장 알림을 보내지 않는다")
                return@withLock
            }
            sendDiaryNotifications()
        }
    }

    fun sendDiaryNotifications() {
        val coursebook = coursebookService.getLatestCoursebook()
        val primaries =
            timetableRepository
                .findByYearAndSemesterAndIsPrimaryTrue(coursebook.year, coursebook.semester)
                .shuffled()
                .let { it.take((it.size * sampleRate).toInt().coerceAtLeast(if (it.isEmpty()) 0 else 1)) }
        val messages =
            primaries
                .mapNotNull { timetable ->
                    val lectures = timetableLectureRepository.findByTimetableId(timetable.id!!).filter { it.lectureId != null }
                    if (lectures.size <= 2) return@mapNotNull null
                    val target = lectures.random()
                    val lecture = lectureRepository.findByIdOrNull(target.lectureId!!) ?: return@mapNotNull null
                    timetable.userId to
                        TargetedPush(
                            title = "이번주 강의일기를 작성해보세요.",
                            body = "최근 수강한 <${lecture.courseTitle}> 강의에 대한 강의일기를 작성해보세요.📔",
                            urlScheme =
                                "snutt://diary?lectureId=${lecture.externalId}" +
                                    "&courseTitle=${URLEncoder.encode(lecture.courseTitle, Charsets.UTF_8)}",
                        )
                }.toMap()
        log.info("강의 일기장 알림 발송: {}건", messages.size)
        pushService.sendTargetedPushes(messages, PushPreferenceType.DIARY)
    }
}
