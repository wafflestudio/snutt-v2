package com.wafflestudio.snutt.api.scheduler

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.common.push.Deeplink
import com.wafflestudio.snutt.core.common.push.DeeplinkResolver
import com.wafflestudio.snutt.core.common.util.SchedulerLock
import com.wafflestudio.snutt.core.common.util.SemesterCalendar
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.diary.service.DiaryPush
import com.wafflestudio.snutt.core.domain.diary.service.DiaryPushQueue
import com.wafflestudio.snutt.core.domain.lecture.model.localizedCourseTitle
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.maillog.service.MailLogService
import com.wafflestudio.snutt.core.domain.notification.model.NotificationTemplate
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

@Component
class ReminderScheduler(
    private val timetableLectureReminderService: TimetableLectureReminderService,
    private val pushService: PushService,
    private val schedulerLock: SchedulerLock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst = ZoneId.of("Asia/Seoul")

    @Scheduled(cron = "0 * * * * *")
    fun fireDueReminders() {
        schedulerLock.withLock("reminder", Duration.ofSeconds(50)) {
            val current = SemesterCalendar.current()
            if (current == null) {
                log.debug("현재 진행 중인 학기가 없어 리마인더를 보내지 않는다")
                return@withLock
            }
            fireDueReminders(ZonedDateTime.now(kst), current)
        }
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    fun deletePastSemesterReminders() {
        schedulerLock.withLock("reminder-cleanup", Duration.ofMinutes(10)) {
            val current = SemesterCalendar.current() ?: return@withLock
            timetableLectureReminderService.deletePastSemesterReminders(current)
        }
    }

    fun fireDueReminders(
        now: ZonedDateTime,
        current: YearAndSemester,
    ) {
        timetableLectureReminderService.processDueReminders(now, current).forEach { push ->
            pushService.sendPushAndNotification(
                userIds = listOf(push.userId),
                message = push.message.copy(isUrgentOnAndroid = true, shouldSendAsDataMessage = true),
                type = NotificationType.NORMAL,
                preferenceType = PushPreferenceType.NORMAL,
            )
        }
    }
}

@Component
class DiaryScheduler(
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val lectureRepository: LectureRepository,
    private val lectureClassTimeRepository: LectureClassTimeRepository,
    private val coursebookService: CoursebookService,
    private val pushService: PushService,
    private val schedulerLock: SchedulerLock,
    private val deeplinkResolver: DeeplinkResolver,
    private val diaryPushQueue: DiaryPushQueue,
    @param:Value("\${snutt.diary.push-sample-rate}") private val sampleRate: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst = ZoneId.of("Asia/Seoul")

    @Scheduled(cron = "0 0 6 * * MON-FRI", zone = "Asia/Seoul")
    fun planDiaryNotificationsScheduled() {
        val current = SemesterCalendar.current()
        if (current == null) {
            log.debug("현재 진행 중인 학기가 없어 일기장 알림을 예약하지 않는다")
            return
        }
        planDiaryNotifications(ZonedDateTime.now(kst), current)
    }

    @Scheduled(cron = "0 * * * * *")
    fun sendDueDiaryNotificationsScheduled() {
        schedulerLock.withLock("diary-dispatch", Duration.ofSeconds(50)) {
            sendDueDiaryNotifications(ZonedDateTime.now(kst))
        }
    }

    fun planDiaryNotifications(
        now: ZonedDateTime,
        target: YearAndSemester,
    ) {
        val pushes = diaryPushes(now, target)
        if (diaryPushQueue.schedule(now.toLocalDate(), pushes)) log.info("강의 일기장 알림 예약: {}건", pushes.size)
    }

    fun sendDueDiaryNotifications(now: ZonedDateTime) {
        send(diaryPushQueue.popDue(now.toLocalDate(), now.toInstant()))
    }

    fun sendDiaryNotificationsNow() {
        val target =
            SemesterCalendar.current()
                ?: coursebookService.getLatestCoursebook().let { YearAndSemester(it.year, it.semester) }
        send(diaryPushes(ZonedDateTime.now(kst), target).map { it.userId to it.lectureId })
    }

    private fun diaryPushes(
        now: ZonedDateTime,
        target: YearAndSemester,
    ): List<DiaryPush> {
        val today = DayOfWeek.fromValue(now.dayOfWeek.value - 1)
        val startOfDay = now.toLocalDate().atStartOfDay(kst)
        val latestSendAt = now.toLocalDate().atTime(LATEST_SEND_TIME).atZone(kst)
        val primaries =
            timetableRepository
                .findByYearAndSemesterAndIsPrimaryTrue(target.year, target.semester)
                .shuffled()
                .let { it.take((it.size * sampleRate).toInt().coerceAtLeast(if (it.isEmpty()) 0 else 1)) }
        val entriesByTimetableId =
            timetableLectureRepository
                .findByTimetableIdIn(primaries.map { it.id!! })
                .filter { it.lectureId != null }
                .groupBy { it.timetableId }
        val classTimesByLectureId =
            lectureClassTimeRepository
                .findAllByLectureIdInOrderById(
                    entriesByTimetableId.values
                        .flatten()
                        .mapNotNull { it.lectureId }
                        .distinct(),
                ).groupBy { it.lectureId }
        return primaries.mapNotNull { timetable ->
            val endMinuteByLectureId =
                entriesByTimetableId[timetable.id].orEmpty().mapNotNull { entry ->
                    val times =
                        entry.overrides?.classPlaceAndTimes
                            ?: classTimesByLectureId[entry.lectureId].orEmpty().map { it.toClassPlaceAndTime() }
                    times.filter { it.day == today }.maxOfOrNull { it.endMinute }?.let { entry.lectureId!! to it }
                }
            val lastEndMinute = endMinuteByLectureId.maxOfOrNull { it.second } ?: return@mapNotNull null
            val delayMinutes = Random.nextInt(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES + 1)
            val sendAt = startOfDay.plusMinutes((lastEndMinute + delayMinutes).toLong())
            if (sendAt.isAfter(latestSendAt)) return@mapNotNull null
            DiaryPush(userId = timetable.userId, lectureId = endMinuteByLectureId.random().first, sendAt = sendAt.toInstant())
        }
    }

    private fun send(targets: List<Pair<Long, Long>>) {
        if (targets.isEmpty()) return
        val lecturesById = lectureRepository.findAllById(targets.map { it.second }.distinct()).associateBy { it.id!! }
        val messages =
            targets
                .mapNotNull { (userId, lectureId) ->
                    val lecture = lecturesById[lectureId] ?: return@mapNotNull null
                    userId to
                        NotificationTemplate.DIARY_WRITE
                            .render(lecture.localizedCourseTitle)
                            .copy(urlScheme = deeplinkResolver.build(Deeplink.Diary(lecture.id!!, lecture.courseTitle)))
                }.toMap()
        log.info("강의 일기장 알림 발송: {}건", messages.size)
        pushService.sendTargetedPushes(messages, PushPreferenceType.DIARY)
    }

    private companion object {
        const val MIN_DELAY_MINUTES = 10
        const val MAX_DELAY_MINUTES = 90
        val LATEST_SEND_TIME: LocalTime = LocalTime.of(22, 0)
    }
}

@Component
class MailLogCleanupScheduler(
    private val mailLogService: MailLogService,
    private val schedulerLock: SchedulerLock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    fun deleteExpiredMailLogs() {
        schedulerLock.withLock("mail-log-cleanup", Duration.ofMinutes(10)) {
            val deleted = mailLogService.deleteExpired()
            log.info("만료된 메일 로그 삭제: {}건", deleted)
        }
    }
}
