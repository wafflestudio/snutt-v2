package com.wafflestudio.snutt.api.scheduler

import com.wafflestudio.snutt.core.common.util.SchedulerLock
import com.wafflestudio.snutt.core.common.util.SemesterCalendar
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.notification.service.TargetedPush
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

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
            val now = ZonedDateTime.now(kst)
            timetableLectureReminderService
                .processDueReminders(now, current)
                .forEach { push ->
                    pushService.sendPushAndNotification(
                        userIds = listOf(push.userId),
                        title = "📚 강의 리마인더",
                        body = push.body,
                        type = NotificationType.NORMAL,
                        preferenceType = PushPreferenceType.NORMAL,
                        urlScheme = "snutt://timetable",
                    )
                }
        }
    }

    fun fireDueReminders(
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
    ) {
        timetableLectureReminderService.processDueReminders(now, current).forEach { push ->
            pushService.sendPushAndNotification(
                userIds = listOf(push.userId),
                title = "📚 강의 리마인더",
                body = push.body,
                type = NotificationType.NORMAL,
                preferenceType = PushPreferenceType.NORMAL,
                urlScheme = "snutt://timetable",
            )
        }
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
    @param:Value("\${snutt.diary.push-sample-rate:0.1}") private val sampleRate: Double,
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
        // 학기 중에는 현재 학기(다음 학기 수강편람이 올라와도), 방학 중에는 최신 수강편람 학기를 대상으로 한다
        val target =
            SemesterCalendar.current()
                ?: coursebookService.getLatestCoursebook()?.let { SemesterCalendar.YearSemester(it.year, it.semester) }
                ?: return
        val primaries =
            timetableRepository
                .findByYearAndSemesterAndIsPrimaryTrue(target.year, target.semester)
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
                                "snutt://diary?lectureId=${lecture.id}" +
                                    "&courseTitle=${URLEncoder.encode(lecture.courseTitle, Charsets.UTF_8)}",
                        )
                }.toMap()
        log.info("강의 일기장 알림 발송: {}건", messages.size)
        pushService.sendTargetedPushes(messages, PushPreferenceType.DIARY)
    }
}
