package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.RecordingPushClient
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.configuration.JobRegistry
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * M7 DoD: 빈자리 알림 잡 — 만석 해제 감지 → push_preference 필터 → FCM + 알림함
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VacancyNotificationJobTest : AbstractBatchIntegrationTest() {
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
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var vacancyNotificationRepository: VacancyNotificationRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var recordingPushClient: RecordingPushClient

    @Autowired
    lateinit var userDeviceRepository: com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository

    @BeforeEach
    fun cleanTables() {
        lectureRepository.deleteAll()
        vacancyNotificationRepository.deleteAll()
        notificationRepository.deleteAll()
        userDeviceRepository.deleteAll()
        recordingPushClient.sentMessages.clear()
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
                    credentialHash = "vacancycred",
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
                    registrationCount = 30,
                    wasFull = true,
                ),
            )
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))

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

        // FCM 발송 + was_full 해제 + 알림함 저장
        assertTrue(recordingPushClient.sentMessages.isNotEmpty())
        assertEquals("fcm-token-1", recordingPushClient.sentMessages[0].fcmRegistrationId)
        assertTrue(recordingPushClient.sentMessages[0].body.contains("빈자리"))
        assertFalse(lectureRepository.findById(lecture.id!!).get().wasFull)
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
                    credentialHash = "vacancyoffcred",
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
                    registrationCount = 30,
                    wasFull = true,
                ),
            )
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))

        jobLauncher.run(
            jobRegistry.getJob("vacancyNotificationJob"),
            org.springframework.batch.core.job.parameters
                .JobParametersBuilder()
                .addLong(
                    "run.id",
                    System.currentTimeMillis(),
                ).toJobParameters(),
        )

        assertTrue(recordingPushClient.sentMessages.isEmpty())
        assertEquals(0, notificationRepository.findAll().size)
    }

    @Autowired
    lateinit var pushPreferenceRepository: com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
}
