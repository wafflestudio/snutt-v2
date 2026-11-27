package com.wafflestudio.snutt.api.scheduler

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.TargetedPushMessage
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminderSchedule
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderScheduleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

class SchedulerIntegrationTest : AbstractApiIntegrationTest() {
    @Autowired
    lateinit var reminderScheduler: ReminderScheduler

    @Autowired
    lateinit var diaryScheduler: DiaryScheduler

    @Autowired
    lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired
    lateinit var timetableLectureReminderRepository: TimetableLectureReminderRepository

    @Autowired
    lateinit var timetableLectureReminderScheduleRepository: TimetableLectureReminderScheduleRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    private fun sentMessages(): List<TargetedPushMessage> {
        val captor = argumentCaptor<List<TargetedPushMessage>>()
        verify(pushClient).sendMessages(captor.capture())
        return captor.firstValue
    }

    @Test
    fun `리마인더가 발화 시각에 푸시를 보내고 다음 발화로 전진한다`() {
        // 2026 가을학기 중 화요일 09:50 KST. 크론 진입점이 Clock으로 현재 학기와 시각을 읽는다.
        clock.setKst(LocalDateTime.of(2026, 9, 15, 9, 50))
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user("reminderuser", email = "reminder@snu.ac.kr")
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-reminder"))
        userDeviceRepository.save(
            UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-reminder-en", language = Language.EN),
        )
        val lecture =
            fixtures.lecture(autumn, courseTitle = "HCI이론 및 실습", courseNumber = "2114.408A") {
                courseTitleEn = "Theories and Practices of HCI"
                instructor = "임하진"
                department = "언론정보학과(연합전공 정보문화학)"
                academicYear = "4학년"
                classification = "전필"
                quota = 25
            }
        val timetableLectureId = fixtures.addLecture(fixtures.timetable(user, autumn), lecture).id!!
        val reminder =
            timetableLectureReminderRepository.save(
                TimetableLectureReminder(timetableLectureId = timetableLectureId, offsetMinutes = -10),
            )
        timetableLectureReminderScheduleRepository.save(
            TimetableLectureReminderSchedule(reminderId = reminder.id!!, day = DayOfWeek.TUESDAY, minute = 9 * 60 + 50),
        )

        reminderScheduler.fireDueReminders()

        val sent = sentMessages().associateBy { it.fcmRegistrationId }
        assertEquals(setOf("fcm-reminder", "fcm-reminder-en"), sent.keys)
        val korean = sent.getValue("fcm-reminder").message
        assertTrue(korean.title.contains("리마인더"))
        assertTrue(korean.body.contains("HCI이론 및 실습"))
        assertTrue(korean.body.contains("10분 전"))
        val english = sent.getValue("fcm-reminder-en").message
        assertEquals("📚 Class reminder", english.title)
        assertEquals("Theories and Practices of HCI starts in 10 minutes.", english.body)

        assertNotNull(timetableLectureReminderRepository.findByTimetableLectureId(timetableLectureId))
        val schedule = timetableLectureReminderScheduleRepository.findByReminderId(reminder.id!!).single()
        assertNotNull(schedule.recentNotifiedAt)
    }

    @Test
    fun `일기장 알림은 그날 수업이 있는 강의로 마지막 수업이 끝난 뒤에 간다`() {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user("diarysched", email = "diarysched@snu.ac.kr")
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-diary"))
        val timetable = fixtures.timetable(user, autumn)
        val wednesday =
            fixtures.lecture(
                autumn,
                courseTitle = "공학연구의 실습 1",
                courseNumber = "400.320",
                lectureNumber = "002",
                credit = 1,
                times = listOf(ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "301-118", 900, 975)),
            )
        val thursday =
            fixtures.lecture(
                autumn,
                courseTitle = "컴퓨터 신기술 특강",
                courseNumber = "4190.101",
                times = listOf(ClassPlaceAndTime(DayOfWeek.THURSDAY, "302-208", 900, 975)),
            )
        listOf(wednesday, thursday).forEach { fixtures.addLecture(timetable, it) }

        // 수요일 06:00 KST에 예약하고, 마지막 수업(16:15 종료) 뒤 최대 지연(90분)이 지난 17:45에 발송 크론이 돈다.
        clock.setKst(LocalDateTime.of(2026, 11, 4, 6, 0))
        diaryScheduler.planDiaryNotificationsScheduled()
        clock.setKst(LocalDateTime.of(2026, 11, 4, 17, 45))
        diaryScheduler.sendDueDiaryNotificationsScheduled()

        val sent = sentMessages().single()
        assertEquals("fcm-diary", sent.fcmRegistrationId)
        assertTrue(sent.message.body.contains("공학연구의 실습 1"))
        assertTrue(notificationRepository.findAll().none { it.userId == user.id })
    }
}
