package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.batch.SugangFixtures.SearchItem
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.SEARCH
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.html
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.slow
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.status
import com.wafflestudio.snutt.batch.vacancy.RegistrationStatus
import com.wafflestudio.snutt.batch.vacancy.SugangSnuRegistrationStatusCrawler
import com.wafflestudio.snutt.batch.vacancy.VacancyNotificationJob
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.TargetedPushMessage
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreference
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationPhase
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationTimeSlot
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

// 잡은 수강신청 인원이 모두 0인 페이지를 받아야 끝난다. stub이 어긋나 무한 루프에 빠지면 실패하도록 시간 제한을 둔다.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VacancyNotificationJobTest : AbstractBatchIntegrationTest() {
    @Autowired
    lateinit var vacancyNotificationJob: VacancyNotificationJob

    @Autowired
    lateinit var lectureRegistrationStatusRepository: LectureRegistrationStatusRepository

    @Autowired
    lateinit var vacancyNotificationRepository: VacancyNotificationRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired
    lateinit var semesterRegistrationPeriodService:
        SemesterRegistrationPeriodService

    private lateinit var autumn: Coursebook

    @BeforeEach
    fun seed() {
        // 2026-09-12 11:30 KST: 13:00 빈자리 신청 시간대 직전
        clock.setKst(LocalDateTime.of(2026, 9, 12, 11, 30))
        autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val now = clock.nowKst()
        semesterRegistrationPeriodService.upsert(
            2026,
            Semester.AUTUMN,
            listOf(
                RegistrationDate(
                    date = now.toLocalDate(),
                    vacantSeatRegistrationTimes =
                        listOf(
                            RegistrationTimeSlot(10 * 60, 11 * 60),
                            RegistrationTimeSlot(13 * 60, 14 * 60),
                            RegistrationTimeSlot(17 * 60, 18 * 60),
                        ),
                    phase = RegistrationPhase.COURSE_CHANGE,
                ),
            ),
        )
    }

    @Test
    fun `만석 해제된 강의 구독자에게 푸시와 알림함이 간다`() {
        val user = fixtures.user("vacancyuser", email = "vacancy@snu.ac.kr")
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-token-1"))
        userDeviceRepository.save(
            UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-token-1-en", language = Language.EN),
        )
        val lecture =
            fixtures.lecture(
                autumn,
                courseTitle = "HCI이론 및 실습",
                courseNumber = "2114.408A",
                lectureNumber = "001",
                credit = 3,
            ) {
                courseTitleEn = "Theories and Practices of HCI"
                instructor = "임하진"
                department = "언론정보학과(연합전공 정보문화학)"
                academicYear = "4학년"
                classification = "전필"
                quota = 25
            }
        lectureRegistrationStatusRepository.save(
            LectureRegistrationStatus(lectureId = lecture.id!!, registrationCount = 25, wasFull = true),
        )
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))
        stubPolls(SearchItem("2114.408A", "001", registrationCount = 24, quota = 25, wasFull = true))

        vacancyNotificationJob.run(null)

        val sent = sentMessages().associateBy { it.fcmRegistrationId }
        assertEquals(setOf("fcm-token-1", "fcm-token-1-en"), sent.keys)
        assertEquals("빈자리 알림", sent.getValue("fcm-token-1").message.title)
        assertEquals(
            "\"HCI이론 및 실습 (001)\" 강의에 빈자리가 생겼습니다.\n13:00에 수강신청 사이트를 확인해보세요!",
            sent.getValue("fcm-token-1").message.body,
        )
        assertEquals("Vacancy alert", sent.getValue("fcm-token-1-en").message.title)
        assertEquals(
            "A seat has opened up in \"Theories and Practices of HCI (001)\".\n" +
                "Check the course registration site at 13:00!",
            sent.getValue("fcm-token-1-en").message.body,
        )
        assertEquals(24, lectureRegistrationStatusRepository.findById(lecture.id!!).get().registrationCount)
        val notification = notificationRepository.findAll().single()
        assertEquals("빈자리 알림", notification.title)
        assertEquals("Vacancy alert", notification.titleEn)
    }

    @Test
    fun `처음 보이는 강의는 상태만 저장하고 푸시하지 않는다`() {
        val user = fixtures.user("firstseen", email = "firstseen@snu.ac.kr")
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-token-3"))
        val lecture =
            fixtures.lecture(
                autumn,
                courseTitle = "운영체제",
                courseNumber = "400.301",
                lectureNumber = "001",
                credit = 3,
            ) {
                instructor = "김준우"
                department = "컴퓨터공학부"
                academicYear = "3학년"
                classification = "전선"
                quota = 24
            }
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))
        stubPolls(SearchItem("400.301", "001", registrationCount = 23, quota = 24, wasFull = true))

        vacancyNotificationJob.run(null)

        verify(pushClient, never()).sendMessages(any())
        assertEquals(0, notificationRepository.findAll().size)
        assertEquals(23, lectureRegistrationStatusRepository.findById(lecture.id!!).get().registrationCount)
    }

    @Test
    fun `알림을 끈 사용자는 제외된다`() {
        val user = fixtures.user("vacancyoff", email = "vacancyoff@snu.ac.kr")
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-token-2"))
        pushPreferenceRepository.save(
            PushPreference(user = user, type = PushPreferenceType.VACANCY_NOTIFICATION, isEnabled = false),
        )
        val lecture =
            fixtures.lecture(
                autumn,
                courseTitle = "경영학을 위한 수학",
                courseNumber = "F31.113",
                lectureNumber = "001",
                credit = 3,
            ) {
                instructor = "안명숙"
                department = "수리과학부"
                academicYear = "1학년"
                category = "수학과학컴퓨팅"
                classification = "교양"
                quota = 50
            }
        lectureRegistrationStatusRepository.save(
            LectureRegistrationStatus(lectureId = lecture.id!!, registrationCount = 50, wasFull = true),
        )
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))
        stubPolls(SearchItem("F31.113", "001", registrationCount = 49, quota = 50, wasFull = true))

        vacancyNotificationJob.run(null)

        verify(pushClient, never()).sendMessages(any())
        assertEquals(1, notificationRepository.findAll().size)
    }

    @Autowired
    lateinit var crawler: SugangSnuRegistrationStatusCrawler

    @Test
    fun `실제 검색 페이지에서 페이지 수와 신청 인원, 취소여석 표시를 읽는다`() {
        sugang.searchPageSequence(html(SugangFixtures.text("search-page.html")))

        assertEquals(28, crawler.getPageCount(2026, Semester.WINTER))
        val statuses = crawler.getRegistrationStatus(2026, Semester.WINTER, listOf(1))

        assertEquals(10, statuses.size)
        assertEquals(RegistrationStatus("1003.271", "001", registrationCount = 35, wasFull = false), statuses.first())
        assertEquals(listOf(99, 99), statuses.filter { it.wasFull }.map { it.registrationCount })
    }

    @Test
    fun `검색 페이지가 5xx면 잠시 뒤 다시 조회한다`() {
        val subscription = subscribedFullLecture()
        sugang.searchPageSequence(status(500), *pollResponses(vacated(subscription)))

        vacancyNotificationJob.run(null)

        assertVacancyNotified(subscription)
        assertEquals(5, sugang.requestsTo(SEARCH))
    }

    @Test
    fun `검색 페이지가 타임아웃되면 잠시 뒤 다시 조회한다`() {
        val subscription = subscribedFullLecture()
        // 늦게라도 도착하면 결과가 달라지는 본문(신청 인원 0)을 둔다. 타임아웃이 안 걸리면 요청이 4번에서 끝난다.
        sugang.searchPageSequence(slow(html(page(closed()))), *pollResponses(vacated(subscription)))

        vacancyNotificationJob.run(null)

        assertVacancyNotified(subscription)
        assertEquals(5, sugang.requestsTo(SEARCH))
    }

    @Test
    fun `검색 페이지 구조가 바뀌면 부하로 보고 잠시 뒤 다시 조회한다`() {
        val subscription = subscribedFullLecture()
        sugang.searchPageSequence(html("<html><body><p>잠시 후 다시 접속해 주세요</p></body></html>"), *pollResponses(vacated(subscription)))

        vacancyNotificationJob.run(null)

        assertVacancyNotified(subscription)
        assertEquals(5, sugang.requestsTo(SEARCH))
    }

    @Test
    fun `검색 건수가 0이면 잠시 뒤 다시 조회한다`() {
        val subscription = subscribedFullLecture()
        sugang.searchPageSequence(html(SugangFixtures.searchPage(emptyList(), totalCount = 0)), *pollResponses(vacated(subscription)))

        vacancyNotificationJob.run(null)

        assertVacancyNotified(subscription)
        assertEquals(5, sugang.requestsTo(SEARCH))
    }

    @Test
    fun `페이지 크롤링이 실패한 청크는 건너뛰고 다음 조회에서 처리한다`() {
        val subscription = subscribedFullLecture()
        val vacated = html(page(vacated(subscription)))
        sugang.searchPageSequence(vacated, status(500), vacated, vacated, html(page(closed())))

        vacancyNotificationJob.run(null)

        assertVacancyNotified(subscription)
        // 실패한 청크에서는 상태를 저장하지 않았으므로, 다음 조회에서 만석 해제를 감지한다.
        assertEquals(6, sugang.requestsTo(SEARCH))
    }

    private fun subscribedFullLecture(): LectureRegistrationStatus {
        val user = fixtures.user("retryuser", email = "retry@snu.ac.kr")
        userDeviceRepository.save(UserDevice(user = user, osType = "ios", fcmRegistrationId = "fcm-retry"))
        val lecture =
            fixtures.lecture(autumn, courseTitle = "운영체제", courseNumber = "400.301", lectureNumber = "001", credit = 3) {
                quota = 30
            }
        vacancyNotificationRepository.save(VacancyNotification(userId = user.id!!, lectureId = lecture.id!!))
        return lectureRegistrationStatusRepository.save(
            LectureRegistrationStatus(lectureId = lecture.id!!, registrationCount = 30, wasFull = true),
        )
    }

    private fun vacated(status: LectureRegistrationStatus) =
        SearchItem("400.301", "001", registrationCount = status.registrationCount - 1, quota = status.registrationCount, wasFull = true)

    private fun closed() = SearchItem("400.301", "001", registrationCount = 0, quota = 30)

    private fun page(vararg items: SearchItem) = SugangFixtures.searchPage(items.toList())

    /** 한 번 조회(페이지 수 + 1페이지)에 [item]을 보여 주고, 다음 조회에서 신청 인원 0인 페이지로 잡을 끝낸다. */
    private fun pollResponses(item: SearchItem) = arrayOf(html(page(item)), html(page(item)), html(page(closed())))

    private fun stubPolls(item: SearchItem) = sugang.searchPageSequence(*pollResponses(item))

    private fun assertVacancyNotified(status: LectureRegistrationStatus) {
        assertEquals(listOf("fcm-retry"), sentMessages().map { it.fcmRegistrationId })
        assertEquals(status.registrationCount - 1, lectureRegistrationStatusRepository.findById(status.lectureId).get().registrationCount)
    }

    @Autowired
    lateinit var pushPreferenceRepository: PushPreferenceRepository

    private fun sentMessages(): List<TargetedPushMessage> {
        val captor = argumentCaptor<List<TargetedPushMessage>>()
        verify(pushClient).sendMessages(captor.capture())
        return captor.firstValue
    }
}
