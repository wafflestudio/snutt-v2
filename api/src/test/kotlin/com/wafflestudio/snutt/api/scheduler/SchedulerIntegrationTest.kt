package com.wafflestudio.snutt.api.scheduler

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.common.push.TargetedPushMessage
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminderSchedule
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderScheduleRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulerIntegrationTest : AbstractApiIntegrationTest() {
    @Autowired
    lateinit var reminderScheduler: ReminderScheduler

    @Autowired
    lateinit var diaryScheduler: DiaryScheduler

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired
    lateinit var timetableRepository: TimetableRepository

    @Autowired
    lateinit var timetableLectureRepository: TimetableLectureRepository

    @Autowired
    lateinit var timetableLectureReminderRepository: TimetableLectureReminderRepository

    @Autowired
    lateinit var timetableLectureReminderScheduleRepository: TimetableLectureReminderScheduleRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    private fun sentMessages(): List<TargetedPushMessage> {
        val captor = argumentCaptor<List<TargetedPushMessage>>()
        verify(pushClient).sendMessages(captor.capture())
        return captor.firstValue
    }

    @Test
    fun `리마인더가 발화 시각에 푸시를 보내고 다음 발화로 전진한다`() {
        val user =
            userRepository.save(
                User(
                    email = "reminder@snu.ac.kr",
                    isEmailVerified = true,
                    nickname = "reminderuser",
                    nicknameTag = "0000",
                    localId = "reminderuser",
                ),
            )
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-reminder"))
        val lecture =
            lectureRepository.save(
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "2114.408A",
                    lectureNumber = "001",
                    courseTitle = "HCI이론 및 실습",
                    instructor = "임하진",
                    department = "언론정보학과(연합전공 정보문화학)",
                    academicYear = "4학년",
                    classification = "전필",
                    credit = 3,
                    quota = 25,
                ),
            )
        val timetable =
            timetableRepository.save(
                Timetable(
                    userId = user.id!!,
                    year = 2026,
                    semester = Semester.AUTUMN,
                    title = "나의 시간표",
                    themeId = 2L,
                    isPrimary = true,
                ),
            )
        val timetableLecture =
            timetableLectureRepository.save(
                TimetableLecture(timetableId = timetable.id!!, lectureId = lecture.id, paletteIndex = 0),
            )
        val timetableLectureId = timetableLecture.id!!

        val now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        val reminder =
            timetableLectureReminderRepository.save(
                TimetableLectureReminder(
                    timetableLectureId = timetableLectureId,
                    offsetMinutes = -10,
                ),
            )
        timetableLectureReminderScheduleRepository.save(
            TimetableLectureReminderSchedule(
                reminderId = reminder.id!!,
                day = DayOfWeek.fromValue(now.dayOfWeek.value - 1),
                minute = now.hour * 60 + now.minute,
            ),
        )

        reminderScheduler.fireDueReminders(now, YearAndSemester(2026, Semester.AUTUMN))

        val sent = sentMessages().single()
        assertEquals("fcm-reminder", sent.fcmRegistrationId)
        assertTrue(sent.message.title.contains("리마인더"))
        assertTrue(sent.message.body.contains("HCI이론 및 실습"))
        assertTrue(sent.message.body.contains("10분 전"))

        val after = timetableLectureReminderRepository.findByTimetableLectureId(timetableLectureId)!!
        val schedule =
            timetableLectureReminderScheduleRepository
                .findByReminderId(after.id!!)
                .single { it.day == DayOfWeek.fromValue(now.dayOfWeek.value - 1) && it.minute == now.hour * 60 + now.minute }
        assertNotNull(schedule.recentNotifiedAt)
    }

    @Test
    fun `일기장 알림은 그날 수업이 있는 강의로 마지막 수업이 끝난 뒤에 간다`() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        val user =
            userRepository.save(
                User(
                    email = "diarysched@snu.ac.kr",
                    isEmailVerified = true,
                    nickname = "diarysched",
                    nicknameTag = "0000",
                    localId = "diarysched",
                ),
            )
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-diary"))
        val timetable =
            timetableRepository.save(
                Timetable(
                    userId = user.id!!,
                    year = 2026,
                    semester = Semester.AUTUMN,
                    title = "나의 시간표",
                    themeId = 2L,
                    isPrimary = true,
                ),
            )
        val wednesday =
            saveLectureWithTimes(
                lectureRepository,
                lectureClassTimeRepository,
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "400.320",
                    lectureNumber = "002",
                    courseTitle = "공학연구의 실습 1",
                    credit = 1,
                ),
                listOf(ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "301-118", 900, 975)),
            )
        val thursday =
            saveLectureWithTimes(
                lectureRepository,
                lectureClassTimeRepository,
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "4190.101",
                    lectureNumber = "001",
                    courseTitle = "컴퓨터 신기술 특강",
                    credit = 3,
                ),
                listOf(ClassPlaceAndTime(DayOfWeek.THURSDAY, "302-208", 900, 975)),
            )
        listOf(wednesday, thursday).forEach {
            timetableLectureRepository.save(TimetableLecture(timetableId = timetable.id!!, lectureId = it.id, paletteIndex = 0))
        }
        val wednesdayMorning = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, ZoneId.of("Asia/Seoul"))

        diaryScheduler.planDiaryNotifications(wednesdayMorning, YearAndSemester(2026, Semester.AUTUMN))
        diaryScheduler.sendDueDiaryNotifications(wednesdayMorning.withHour(17).withMinute(45))

        val sent = sentMessages().single()
        assertEquals("fcm-diary", sent.fcmRegistrationId)
        assertTrue(sent.message.body.contains("공학연구의 실습 1"))
        assertTrue(notificationRepository.findAll().none { it.userId == user.id })
    }
}
