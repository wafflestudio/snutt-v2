package com.wafflestudio.snutt.api.scheduler

import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.ZonedDateTime

// 강의 리마인더: 매분 next_day/next_minute 인덱스로 발화 대상 조회 (PLAN.md §4)
@Component
class ReminderScheduler(
    private val timetableLectureReminderRepository: TimetableLectureReminderRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableRepository: TimetableRepository,
    private val timetableService: TimetableService,
    private val pushService: PushService,
) {
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    fun fireDueReminders() {
        val now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        val due =
            timetableLectureReminderRepository.findByNextDayAndNextMinute(
                now.dayOfWeek.value - 1,
                now.hour * 60 + now.minute,
            )
        due.forEach { reminder ->
            fire(reminder, now)
        }
    }

    private fun fire(
        reminder: TimetableLectureReminder,
        now: ZonedDateTime,
    ) {
        val timetableLecture =
            timetableLectureRepository.findById(reminder.timetableLectureId).orElse(null) ?: return
        val timetable =
            timetableRepository.findById(timetableLecture.timetableId).orElse(null) ?: return
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
        // 발화 후 recentNotifiedAt을 기록하고 다음 발화 시각으로 전진한다
        reminder.recentNotifiedAt = now.toInstant()
        reminder.recomputeNextFire(now.toInstant().plusSeconds(60))
    }
}

// 강의 일기장 알림: 월/수/금 19시 (KST) — 대표 시간표의 강의 하나를 골라 안내 (PLAN.md §4)
@Component
class DiaryScheduler(
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val lectureRepository: com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository,
    private val coursebookService: com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService,
    private val pushService: PushService,
) {
    @Scheduled(cron = "0 0 19 * * MON,WED,FRI", zone = "Asia/Seoul")
    @Transactional
    fun sendDiaryNotifications() {
        val coursebook = coursebookService.getLatestCoursebook()
        val primaries = timetableRepository.findByYearAndSemesterAndIsPrimaryTrue(coursebook.year, coursebook.semester)
        primaries.forEach { timetable ->
            val lectures = timetableLectureRepository.findByTimetableId(timetable.id!!).filter { it.lectureId != null }
            val target = lectures.randomOrNull() ?: return@forEach
            val lecture = lectureRepository.findById(target.lectureId!!).orElse(null) ?: return@forEach
            pushService.sendPushAndNotification(
                userIds = listOf(timetable.userId),
                title = "이번주 강의일기를 작성해보세요.",
                body = "최근 수강한 <${lecture.courseTitle}> 강의에 대한 강의일기를 작성해보세요.📔",
                type = NotificationType.FEATURE_NEW,
                preferenceType = PushPreferenceType.DIARY,
                urlScheme = "snutt://diary?lectureId=${lecture.externalId}&courseTitle=${lecture.courseTitle}",
            )
        }
    }
}
