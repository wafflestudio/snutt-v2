package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.batch.vacancy.RegistrationStatus
import com.wafflestudio.snutt.batch.vacancy.SugangSnuRegistrationStatusCrawler
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.RecordingPushClient
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreference
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationPhase
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationTimeSlot
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.configuration.JobRegistry
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.ZoneId
import java.time.ZonedDateTime

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VacancyNotificationJobTest : AbstractBatchIntegrationTest() {
    @MockitoBean
    lateinit var crawler: SugangSnuRegistrationStatusCrawler

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("batch_vacancy_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var jobLauncher: JobLauncher

    @Autowired
    lateinit var jobRegistry: JobRegistry

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureRegistrationStatusRepository: LectureRegistrationStatusRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var vacancyNotificationRepository: VacancyNotificationRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var recordingPushClient: RecordingPushClient

    @Autowired
    lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var semesterRegistrationPeriodService:
        SemesterRegistrationPeriodService

    @BeforeEach
    fun cleanTables() {
        lectureRegistrationStatusRepository.deleteAll()
        lectureRepository.deleteAll()
        vacancyNotificationRepository.deleteAll()
        notificationRepository.deleteAll()
        userDeviceRepository.deleteAll()
        recordingPushClient.sentMessages.clear()

        if (!coursebookRepository.existsByYearAndSemester(2026, Semester.AUTUMN)) {
            coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        }
        val now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        val currentMinute = now.hour * 60 + now.minute
        semesterRegistrationPeriodService.upsert(
            2026,
            Semester.AUTUMN,
            listOf(
                RegistrationDate(
                    date = now.toLocalDate(),
                    vacantSeatRegistrationTimes = listOf(RegistrationTimeSlot(currentMinute, currentMinute + 1)),
                    phase = RegistrationPhase.COURSE_CHANGE,
                ),
            ),
        )
    }

    @Test
    fun `만석 해제된 강의 구독자에게 푸시와 알림함이 간다`() {
        val user =
            userRepository.save(
                User(
                    email = "vacancy@snu.ac.kr",
                    isEmailVerified = true,
                    nickname = "vacancyuser",
                    localId = "vacancyuser",
                ),
            )
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-token-1"))
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
        lectureRegistrationStatusRepository.save(
            LectureRegistrationStatus(lectureId = lecture.id!!, registrationCount = 25, wasFull = true),
        )
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))
        whenever(crawler.getPageCount(any(), any())).thenReturn(1)
        whenever(crawler.getRegistrationStatus(any(), any(), any()))
            .thenReturn(listOf(RegistrationStatus("2114.408A", "001", registrationCount = 24, wasFull = true)))

        val status = jobLauncher.run(jobRegistry.getJob("vacancyNotificationJob"), runIdParameters()).status
        assertEquals(BatchStatus.COMPLETED, status)

        assertTrue(recordingPushClient.sentMessages.isNotEmpty())
        assertEquals("fcm-token-1", recordingPushClient.sentMessages[0].fcmRegistrationId)
        assertTrue(recordingPushClient.sentMessages[0].body.contains("빈자리"))
        assertEquals(24, lectureRegistrationStatusRepository.findById(lecture.id!!).get().registrationCount)
        assertEquals(1, notificationRepository.findAll().size)
    }

    @Test
    fun `알림을 끈 사용자는 제외된다`() {
        val user =
            userRepository.save(
                User(
                    email = "vacancyoff@snu.ac.kr",
                    isEmailVerified = true,
                    nickname = "vacancyoff",
                    localId = "vacancyoff",
                ),
            )
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-token-2"))
        pushPreferenceRepository.save(
            PushPreference(user = user, type = PushPreferenceType.VACANCY_NOTIFICATION, isEnabled = false),
        )
        val lecture =
            lectureRepository.save(
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
            )
        lectureRegistrationStatusRepository.save(
            LectureRegistrationStatus(lectureId = lecture.id!!, registrationCount = 50, wasFull = true),
        )
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))
        whenever(crawler.getPageCount(any(), any())).thenReturn(1)
        whenever(crawler.getRegistrationStatus(any(), any(), any()))
            .thenReturn(listOf(RegistrationStatus("F31.113", "001", registrationCount = 49, wasFull = true)))

        jobLauncher.run(jobRegistry.getJob("vacancyNotificationJob"), runIdParameters())

        assertTrue(recordingPushClient.sentMessages.isEmpty())
        assertEquals(1, notificationRepository.findAll().size)
    }

    @Autowired
    lateinit var pushPreferenceRepository: PushPreferenceRepository

    private fun runIdParameters(): JobParameters = JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters()
}
