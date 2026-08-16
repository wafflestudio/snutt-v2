package com.wafflestudio.snutt.api.scheduler

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.RecordingPushClient
import com.wafflestudio.snutt.core.common.util.SemesterCalendar
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.ZoneId
import java.time.ZonedDateTime

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("scheduler_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

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
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var recordingPushClient: RecordingPushClient

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @BeforeEach
    fun cleanTables() {
        timetableLectureReminderRepository.deleteAll()
        timetableLectureRepository.deleteAll()
        timetableRepository.deleteAll()
        lectureRepository.deleteAll()
        userDeviceRepository.deleteAll()
        coursebookRepository.deleteAll()
        recordingPushClient.sentMessages.clear()
    }

    @Test
    fun `리마인더가 발화 시각에 푸시를 보내고 다음 발화로 전진한다`() {
        val user =
            userRepository.save(
                User(
                    email = "reminder@snu.ac.kr",
                    isEmailVerified = true,
                    nickname = "reminderuser",
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
                ),
            )
        val timetableLecture =
            timetableLectureRepository.save(
                TimetableLecture(timetableId = timetable.id!!, lectureId = lecture.id, colorIndex = 1),
            )
        val timetableLectureId = timetableLecture.id!!

        val now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        timetableLectureReminderRepository.save(
            TimetableLectureReminder(
                timetableLectureId = timetableLectureId,
                offsetMinutes = -10,
                scheduleList =
                    listOf(
                        Schedule(
                            day = DayOfWeek.getOfValue(now.dayOfWeek.value - 1)!!,
                            minute = now.hour * 60 + now.minute,
                        ),
                    ),
                nextDay = now.dayOfWeek.value - 1,
                nextMinute = now.hour * 60 + now.minute,
            ),
        )

        reminderScheduler.fireDueReminders(now, SemesterCalendar.YearSemester(2026, Semester.AUTUMN))

        assertTrue(recordingPushClient.sentMessages.isNotEmpty())
        assertEquals("fcm-reminder", recordingPushClient.sentMessages[0].fcmRegistrationId)
        assertTrue(recordingPushClient.sentMessages[0].title.contains("리마인더"))
        assertTrue(recordingPushClient.sentMessages[0].body.contains("HCI이론 및 실습"))
        assertTrue(recordingPushClient.sentMessages[0].body.contains("10분 전"))

        val after = timetableLectureReminderRepository.findByTimetableLectureId(timetableLectureId)!!
        assertTrue(after.recentNotifiedAt != null)
        val nextMinute = after.nextDay!! * 1440 + after.nextMinute!!
        val nowMinute = now.dayOfWeek.value * 1440 + now.hour * 60 + now.minute
        assertTrue(nextMinute > nowMinute || nextMinute + 7 * 1440 > nowMinute)
    }

    @Test
    fun `일기장 알림이 대표 시간표 사용자에게 간다`() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        val user =
            userRepository.save(
                User(
                    email = "diarysched@snu.ac.kr",
                    isEmailVerified = true,
                    nickname = "diarysched",
                    localId = "diarysched",
                ),
            )
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-diary"))
        val lecture =
            lectureRepository.save(
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "400.320",
                    lectureNumber = "002",
                    courseTitle = "공학연구의 실습 1",
                    instructor = "이제희",
                    department = "컴퓨터공학부",
                    academicYear = "3학년",
                    classification = "전선",
                    credit = 1,
                    quota = 20,
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
        val more =
            listOf(
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "E43.101",
                    lectureNumber = "001",
                    courseTitle = "건강과 삶",
                    instructor = "김부석",
                    department = "체육교육과",
                    academicYear = "1학년",
                    category = "예술과 체육",
                    categoryPre2025 = "체육",
                    classification = "교양",
                    credit = 1,
                    quota = 30,
                ),
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "F31.113",
                    lectureNumber = "001",
                    courseTitle = "경영학을 위한 수학",
                    instructor = "안명숙",
                    department = "수리과학부",
                    academicYear = "1학년",
                    category = "수학과학컴퓨팅",
                    classification = "교양",
                    credit = 3,
                    quota = 50,
                ),
            ).map(lectureRepository::save)
        (listOf(lecture) + more).forEach {
            timetableLectureRepository.save(TimetableLecture(timetableId = timetable.id!!, lectureId = it.id, colorIndex = 1))
        }

        diaryScheduler.sendDiaryNotifications()

        assertTrue(recordingPushClient.sentMessages.isNotEmpty())
        assertTrue(recordingPushClient.sentMessages[0].title.contains("강의일기"))
        assertTrue(recordingPushClient.sentMessages[0].body.contains("강의일기를 작성해보세요"))
        assertTrue(notificationRepository.findAll().none { it.userId == user.id })
    }
}
