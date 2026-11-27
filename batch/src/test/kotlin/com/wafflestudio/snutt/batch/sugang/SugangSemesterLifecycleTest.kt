package com.wafflestudio.snutt.batch.sugang

import com.wafflestudio.snutt.batch.SugangFixtures
import com.wafflestudio.snutt.batch.SugangFixtures.SearchItem
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.html
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.json
import com.wafflestudio.snutt.batch.SugangXlsxFixture.RowData
import com.wafflestudio.snutt.batch.vacancy.VacancyNotificationJob
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.PushMessage
import com.wafflestudio.snutt.core.domain.building.repository.LectureBuildingRepository
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationPhase
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import com.wafflestudio.snutt.core.domain.vacancy.service.VacancyNotificationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 한 학기의 생애주기를 한 흐름으로 따라간다.
 * 신규 학기 감지 → 첫 동기화(강의·수업시간·영문 필드·건물·수강신청 일정) → 사용자가 시간표에 담음
 * → 다음 동기화의 변경이 시간표·리마인더·알림함·푸시에 반영됨 → 수강신청변경 기간의 빈자리 알림.
 * 분기별 사이드케이스는 [SugangSyncDiffTest].
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SugangSemesterLifecycleTest : SugangSyncScenarioTest() {
    @Autowired lateinit var lectureBuildingRepository: LectureBuildingRepository

    @Autowired lateinit var semesterRegistrationPeriodService: SemesterRegistrationPeriodService

    @Autowired lateinit var vacancyNotificationService: VacancyNotificationService

    @Autowired lateinit var vacancyNotificationRepository: VacancyNotificationRepository

    @Autowired lateinit var vacancyNotificationJob: VacancyNotificationJob

    @Autowired lateinit var lectureRegistrationStatusRepository: LectureRegistrationStatusRepository

    private val operatingSystems =
        RowData(
            courseNumber = "400.318",
            lectureNumber = "001",
            courseTitle = "운영체제",
            courseTitleEn = "Operating Systems",
            classTime = "월(09:30~10:45)/수(09:30~10:45)",
            place = "301-101/301-101",
            instructor = "김교수",
            instructorEn = "Kim, Gyosu",
            credit = 3,
            quota = 60,
        )
    private val dataStructures =
        RowData(
            courseNumber = "400.319",
            lectureNumber = "001",
            courseTitle = "자료구조",
            classTime = "화(11:00~12:15)",
            place = "302-208",
            instructor = "박교수",
            credit = 3,
        )
    private val writing =
        RowData(
            classification = "교양",
            department = "기초교육원",
            academicYear = "1학년",
            courseNumber = "031.001",
            lectureNumber = "001",
            courseTitle = "글쓰기의 기초",
            courseTitleEn = "Basic Writing",
            classTime = "목(14:00~15:15)",
            place = "38-422",
            instructor = "이교수",
            credit = 3,
            quota = 30,
            quotaText = "30 (20)",
            classificationEn = "Liberal Education",
        )
    private val closing =
        RowData(courseNumber = "400.999", lectureNumber = "001", courseTitle = "폐강될 강의", classTime = "금(10:00~11:15)", place = "301-118")

    @Test
    fun `신규 학기 생성부터 변경 반영, 빈자리 알림까지 한 학기를 따라간다`() {
        // --- 0. 직전 학기(여름)가 최신 수강편람이고, 그 학기 강의를 담은 사용자와 빈자리 구독이 있다 ---
        val summer = fixtures.coursebook(2026, Semester.SUMMER)
        val summerLecture = fixtures.lecture(summer, courseTitle = "여름 운영체제", courseNumber = "400.318", lectureNumber = "001")
        val alice = userWithDevice("alice")
        val bob = userWithDevice("bob", Language.EN)
        val carol = userWithDevice("carol")
        val aliceSummer = fixtures.timetable(alice, summer, title = "여름 시간표")
        fixtures.addLecture(aliceSummer, summerLecture)
        vacancyNotificationRepository.save(VacancyNotification(userId = carol.id!!, lectureId = summerLecture.id!!))

        // --- 1. 수강편람 사이트에 2학기가 열린다: 신규 학기 생성과 첫 동기화 ---
        clock.setKst(LocalDateTime.of(2026, 7, 20, 10, 0))
        stubSite(
            listOf(operatingSystems, dataStructures, writing, closing),
            mainPage =
                SugangFixtures.mainPageWithSchedule(
                    listOf(
                        "장바구니 담기" to "2026-08-03(월) ~ 2026-08-05(수)",
                        "재학생 선착순 수강신청" to "2026-08-11(화) ~ 2026-08-12(수)",
                        "수강신청변경" to "2026-09-01(화) ~ 2026-09-07(월)",
                    ),
                ),
        )
        sugang.snuMapSearch("38", json(String(javaClass.getResourceAsStream("/fixtures/snumap/search-38.json")!!.readBytes())))

        sugangSnuSyncJob.run(null)

        assertEquals(
            listOf(2026 to Semester.SUMMER, 2026 to Semester.AUTUMN),
            coursebookRepository.findAll().map { it.year to it.semester }.sortedBy { it.second.value },
        )
        // 전체 공지(알림함 + 언어별 토픽 푸시)
        val global = notificationRepository.findAll().single { it.userId == null }
        assertEquals(NotificationType.COURSEBOOK, global.type)
        assertEquals("2026년도 2학기 수강편람이 추가되었습니다.", global.message)
        assertEquals("The 2026 Fall course book has been added.", global.messageEn)
        val topics = argumentCaptor<String>()
        val topicMessages = argumentCaptor<PushMessage>()
        verify(pushClient, times(2)).sendTopicMessage(topics.capture(), topicMessages.capture())
        assertEquals(
            listOf("global" to "신규 수강편람", "global_en" to "New course book"),
            topics.allValues.zip(topicMessages.allValues.map { it.title }),
        )
        // 새 학기가 열리면 이전 학기 빈자리 구독은 모두 지운다.
        assertEquals(0, vacancyNotificationRepository.count())

        val os = lecture("400.318")
        assertEquals("운영체제", os.courseTitle)
        assertEquals("김교수", os.instructor)
        assertEquals(60, os.quota)
        assertEquals("Operating Systems", os.courseTitleEn)
        assertEquals("Kim, Gyosu", os.instructorEn)
        assertEquals("Elective", os.classificationEn)
        assertEquals(
            listOf(
                ClassPlaceAndTime(DayOfWeek.MONDAY, "301-101", 570, 645),
                ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "301-101", 570, 645),
            ),
            classTimes(os),
        )
        assertEquals("김교수", courseRepository.findById(os.courseId!!).get().instructor)
        val wr = lecture("031.001")
        assertEquals(30, wr.quota)
        assertEquals(10, wr.freshmanQuota)
        assertEquals("Liberal Education", wr.classificationEn)
        assertEquals(4, lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN).size)
        // 관악 건물(38동)은 SNU 지도에서 이름과 좌표를 받아 온다. 검색 결과가 없는 301·302동은 저장하지 않는다.
        assertEquals(listOf("38" to "해동학술정보실"), lectureBuildingRepository.findAll().map { it.buildingNumber to it.buildingNameKor })
        // 수강신청 일정: 장바구니는 빼고, 재학생 선착순과 수강신청변경을 날짜별로 펼친다.
        val dates = semesterRegistrationPeriodService.getByYearAndSemester(2026, Semester.AUTUMN)!!.registrationPeriodList
        assertEquals(
            listOf(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 12)) +
                (1..7).map { LocalDate.of(2026, 9, it) },
            dates.map { it.date },
        )
        assertEquals(RegistrationPhase.CURRENT_STUDENT, dates.first().phase)
        assertEquals(RegistrationPhase.COURSE_CHANGE, dates.last().phase)
        // 같은 강좌번호의 여름학기 강의와 시간표는 그대로다.
        assertEquals("여름 운영체제", lectureRepository.findById(summerLecture.id!!).get().courseTitle)
        assertEquals(listOf(summerLecture.id), timetableLectureIds(aliceSummer))

        // --- 2. 사용자들이 시간표에 담고, 관심강좌·빈자리 알림·리마인더를 건다 ---
        clock.setKst(LocalDateTime.of(2026, 7, 21, 9, 0))
        val autumn = coursebookRepository.findAll().single { it.semester == Semester.AUTUMN }.let { Coursebook(it.year, it.semester) }
        val aliceTable = fixtures.timetable(alice, autumn)
        val bobTable = fixtures.timetable(bob, autumn, title = "bob's")
        val carolTable = fixtures.timetable(carol, autumn, title = "캐롤")
        val aliceOs = addLecture(alice, aliceTable, os)
        addLecture(alice, aliceTable, lecture("400.319"))
        setReminder(alice, aliceTable, aliceOs, TimetableLectureReminderOption.TEN_MINUTES_BEFORE)
        assertEquals(listOf(Schedule(DayOfWeek.MONDAY, 560), Schedule(DayOfWeek.WEDNESDAY, 560)), reminderSchedules(aliceOs))
        addLecture(bob, bobTable, os)
        bookmarkService.addLecture(bob.id!!, wr.id!!)
        addLecture(carol, carolTable, wr)
        addLecture(carol, carolTable, lecture("400.999"))
        vacancyNotificationService.addVacancyNotification(carol.id!!, wr.id!!)
        clearPushes()

        // --- 3. 다음 동기화: 시간·강의실, 교수, 정원이 바뀌고 한 강의가 폐강되고 한 강의가 새로 열린다 ---
        clock.setKst(LocalDateTime.of(2026, 8, 3, 10, 0))
        val newLecture = RowData(courseNumber = "400.400", lectureNumber = "001", courseTitle = "새 강의", classTime = "금(15:00~16:15)")
        sync(
            listOf(
                operatingSystems.copy(classTime = "월(11:00~12:15)/수(11:00~12:15)", place = "301-102/301-102"),
                dataStructures.copy(instructor = "최교수"),
                writing.copy(quota = 25, quotaText = "25 (15)"),
                newLecture,
            ),
        )

        assertEquals(os.id, lecture("400.318").id)
        assertEquals(
            listOf(
                ClassPlaceAndTime(DayOfWeek.MONDAY, "301-102", 660, 735),
                ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "301-102", 660, 735),
            ),
            classTimes(os),
        )
        // 리마인더는 새 시간 기준 10분 전으로 다시 잡힌다.
        assertEquals(listOf(Schedule(DayOfWeek.MONDAY, 650), Schedule(DayOfWeek.WEDNESDAY, 650)), reminderSchedules(aliceOs))
        // 교수가 바뀐 강의는 (강좌번호, 교수)로 강의평 과목을 다시 잇는다.
        assertEquals("최교수", courseRepository.findById(lecture("400.319").courseId!!).get().instructor)
        assertEquals(25, lecture("031.001").quota)
        assertEquals(null, lectureOrNull("400.999"))
        assertEquals(listOf(wr.id), timetableLectureIds(carolTable))
        assertEquals(listOf(os.id, lecture("400.319").id), timetableLectureIds(aliceTable))

        // 알림함: 시간표 강의는 시간표 딥링크, 관심강좌는 관심강좌 딥링크, 폐강은 알림함 딥링크
        assertEquals(
            listOf(
                "2026-2학기 '나의 시간표' 시간표의 '운영체제' 강의가 업데이트 되었습니다.(항목: 강의 시간/장소)" to
                    deeplink("timetable-lecture", "timetableId=${aliceTable.id}&lectureId=${os.id}"),
                "2026-2학기 '나의 시간표' 시간표의 '자료구조' 강의가 업데이트 되었습니다.(항목: 교수)" to
                    deeplink("timetable-lecture", "timetableId=${aliceTable.id}&lectureId=${lecture("400.319").id}"),
            ),
            notificationsOf(alice).map { it.message to it.deeplink },
        )
        assertEquals(
            listOf(
                "2026 Fall · \"bob's\" timetable: \"Operating Systems\" was updated. (changed: Class time/place)" to
                    deeplink("timetable-lecture", "timetableId=${bobTable.id}&lectureId=${os.id}"),
                "2026 Fall · Bookmarks: \"Basic Writing\" was updated. (changed: Quota)" to
                    deeplink("bookmarks", "year=2026&semester=AUTUMN&lectureId=${wr.id}"),
            ),
            notificationsOf(bob).map { it.messageEn to it.deeplink },
        )
        assertEquals(
            listOf(
                NotificationType.LECTURE_UPDATE to "2026-2학기 '캐롤' 시간표의 '글쓰기의 기초' 강의가 업데이트 되었습니다.(항목: 정원)",
                NotificationType.LECTURE_REMOVE to "2026-2학기 '캐롤' 시간표의 '폐강될 강의' 강의가 폐강되어 삭제되었습니다.",
            ),
            notificationsOf(carol).map { it.type to it.message },
        )
        // 푸시: 사용자마다 한 통, 기기 언어로, 알림함 딥링크
        val pushes = sentPushes().associateBy({ it.fcmRegistrationId }, { it.message })
        assertEquals(
            mapOf(
                "fcm-alice" to "강의 2개가 변경되었습니다. 알림함에서 자세히 확인하세요.",
                "fcm-bob" to "1 lecture changed. Check your notifications for details.",
                "fcm-carol" to "강의 1개가 변경, 1개가 삭제되었습니다. 알림함에서 자세히 확인하세요.",
            ),
            pushes.mapValues { it.value.body },
        )
        assertEquals("Course book update", pushes.getValue("fcm-bob").title)
        assertTrue(pushes.values.all { it.urlScheme == deeplink("notifications") })
        // 바뀐 강의를 담은 시간표는 동기화 시각으로 갱신 시각이 바뀐다(클라이언트가 다시 받도록).
        listOf(aliceTable, bobTable, carolTable).forEach { table ->
            assertEquals(clock.instant(), timetableRepository.findById(table.id!!).get().updatedAt)
        }
        assertNotEquals(clock.instant(), timetableRepository.findById(aliceSummer.id!!).get().updatedAt)
        // 같은 학기 재동기화이므로 신규 학기 공지는 다시 보내지 않는다.
        assertEquals(1, notificationRepository.findAll().count { it.userId == null })

        // --- 4. 수강신청변경 기간(9/2 12:30)에 빈자리 알림 잡이 돈다 ---
        clearPushes()
        clock.setKst(LocalDateTime.of(2026, 9, 2, 12, 30))
        val full = html(SugangFixtures.searchPage(listOf(SearchItem("031.001", "001", registrationCount = 25, quota = 25, wasFull = true))))
        val vacant =
            html(SugangFixtures.searchPage(listOf(SearchItem("031.001", "001", registrationCount = 24, quota = 25, wasFull = true))))
        val closed = html(SugangFixtures.searchPage(listOf(SearchItem("031.001", "001", registrationCount = 0, quota = 25))))
        // 조회마다 페이지 수(1페이지)와 1페이지를 받는다: 만석(처음 보는 강의라 저장만) → 한 자리 빔(알림) → 신청 인원 0(잡 종료)
        sugang.searchPageSequence(full, full, vacant, vacant, closed)

        vacancyNotificationJob.run(null)

        // 동기화로 바뀐 정원(25)이 만석 기준이 된다. 구독자(carol)에게만 간다. 관심강좌만 담은 bob은 대상이 아니다.
        val vacancyPush = sentPushes().single()
        assertEquals("fcm-carol", vacancyPush.fcmRegistrationId)
        assertEquals("빈자리 알림", vacancyPush.message.title)
        assertEquals("\"글쓰기의 기초 (001)\" 강의에 빈자리가 생겼습니다.\n13:00에 수강신청 사이트를 확인해보세요!", vacancyPush.message.body)
        assertEquals(deeplink("vacancy"), vacancyPush.message.urlScheme)
        assertTrue(vacancyPush.message.isUrgentOnAndroid)
        assertEquals(NotificationType.LECTURE_VACANCY, notificationsOf(carol).last().type)
        assertEquals(24, lectureRegistrationStatusRepository.findById(wr.id!!).get().registrationCount)
        assertTrue(notificationsOf(bob).none { it.type == NotificationType.LECTURE_VACANCY })
    }
}
