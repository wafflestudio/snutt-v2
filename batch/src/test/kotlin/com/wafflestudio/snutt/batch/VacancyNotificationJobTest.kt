package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.batch.vacancy.RegistrationStatus
import com.wafflestudio.snutt.batch.vacancy.SugangSnuRegistrationStatusCrawler
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.RecordingPushClient
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationPhase
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationTimeSlot
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
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * 빈자리 알림 잡: 크롤링한 실시간 재안인원이 저장된 만석 인원보다 줄어들면
 * 구독자에게 FCM + 알림함 저장. 크롤러는 스텁한다
 */
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
    lateinit var userDeviceRepository: com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository

    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var semesterRegistrationPeriodService:
        com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService

    @BeforeEach
    fun cleanTables() {
        lectureRegistrationStatusRepository.deleteAll()
        lectureRepository.deleteAll()
        vacancyNotificationRepository.deleteAll()
        notificationRepository.deleteAll()
        userDeviceRepository.deleteAll()
        recordingPushClient.sentMessages.clear()

        // 잡은 대상 학기를 coursebook에서 구하고, 빈자리 조회 시간대에서만 발송한다
        if (!coursebookRepository.existsByYearAndSemester(2026, Semester.AUTUMN)) {
            coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        }
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
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
        userDeviceRepository.save(
            com.wafflestudio.snutt.core.domain.device.model.UserDevice(
                user = user,
                osType = "ios",
                fcmRegistrationId = "fcm-token-1",
            ),
        )
        val lecture =
            lectureRepository.save(
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "4190.111",
                    lectureNumber = "001",
                    courseTitle = "만석강의",
                    instructor = "교수",
                    quota = 40,
                ),
            )
        lectureRegistrationStatusRepository.save(
            LectureRegistrationStatus(lectureId = lecture.id!!, registrationCount = 40, wasFull = true),
        )
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))
        whenever(crawler.getPageCount(any(), any())).thenReturn(1)
        whenever(crawler.getRegistrationStatus(any(), any(), any()))
            .thenReturn(listOf(RegistrationStatus("4190.111", "001", registrationCount = 39, wasFull = true)))

        val status =
            jobLauncher
                .run(
                    jobRegistry.getJob("vacancyNotificationJob"),
                    org.springframework.batch.core.job.parameters
                        .JobParametersBuilder()
                        .addLong(
                            "run.id",
                            System.currentTimeMillis(),
                        ).toJobParameters(),
                ).status
        assertEquals(BatchStatus.COMPLETED, status)

        // FCM 발송 + 재안인원 반영 + 알림함 저장
        assertTrue(recordingPushClient.sentMessages.isNotEmpty())
        assertEquals("fcm-token-1", recordingPushClient.sentMessages[0].fcmRegistrationId)
        assertTrue(recordingPushClient.sentMessages[0].body.contains("빈자리"))
        assertEquals(39, lectureRegistrationStatusRepository.findById(lecture.id!!).get().registrationCount)
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
        userDeviceRepository.save(
            com.wafflestudio.snutt.core.domain.device.model.UserDevice(
                user = user,
                osType = "ios",
                fcmRegistrationId = "fcm-token-2",
            ),
        )
        pushPreferenceRepository.save(
            com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreference(
                user = user,
                type = com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType.VACANCY_NOTIFICATION,
                isEnabled = false,
            ),
        )
        val lecture =
            lectureRepository.save(
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "4190.112",
                    lectureNumber = "001",
                    courseTitle = "만석강의2",
                    instructor = "교수",
                    quota = 40,
                ),
            )
        lectureRegistrationStatusRepository.save(
            LectureRegistrationStatus(lectureId = lecture.id!!, registrationCount = 40, wasFull = true),
        )
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))
        whenever(crawler.getPageCount(any(), any())).thenReturn(1)
        whenever(crawler.getRegistrationStatus(any(), any(), any()))
            .thenReturn(listOf(RegistrationStatus("4190.112", "001", registrationCount = 39, wasFull = true)))

        jobLauncher.run(
            jobRegistry.getJob("vacancyNotificationJob"),
            org.springframework.batch.core.job.parameters
                .JobParametersBuilder()
                .addLong(
                    "run.id",
                    System.currentTimeMillis(),
                ).toJobParameters(),
        )

        // 푸시 설정은 FCM만 거르고 알림함에는 남는다 (v1 동일)
        assertTrue(recordingPushClient.sentMessages.isEmpty())
        assertEquals(1, notificationRepository.findAll().size)
    }

    @Autowired
    lateinit var pushPreferenceRepository: com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
}
