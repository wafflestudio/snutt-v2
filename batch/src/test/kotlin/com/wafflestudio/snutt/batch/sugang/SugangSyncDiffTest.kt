package com.wafflestudio.snutt.batch.sugang

import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.CONDITION
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.EXCEL
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.MAIN
import com.wafflestudio.snutt.batch.SugangSnuStubs.Companion.json
import com.wafflestudio.snutt.batch.SugangXlsxFixture.RowData
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkLectureRepository
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.service.CustomTimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureModifyRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * 수강편람 동기화의 diff 분기(`SugangSnuSyncService.syncLectures`·`syncUserLectures`)를 하나씩 덮는다.
 * 모든 테스트는 2학기 수강편람이 있는 상태에서 잡을 두 번 이상 돌린다: 첫 동기화로 강의를 적재하고, 사용자가 실제 서비스로
 * 시간표에 담은 뒤, 바뀐 엑셀로 다시 동기화한다. 골든패스는 [SugangSemesterLifecycleTest].
 */
class SugangSyncDiffTest : SugangSyncScenarioTest() {
    @Autowired lateinit var bookmarkLectureRepository: BookmarkLectureRepository

    @Autowired lateinit var vacancyNotificationRepository: VacancyNotificationRepository

    @Autowired lateinit var lectureRegistrationStatusRepository: LectureRegistrationStatusRepository

    private lateinit var autumn: Coursebook

    private val algo =
        RowData(
            courseNumber = "400.100",
            lectureNumber = "001",
            courseTitle = "알고리즘",
            classTime = "월(10:00~11:15)",
            place = "301-101",
            instructor = "김교수",
            credit = 3,
            quota = 40,
        )
    private val compiler =
        RowData(
            courseNumber = "400.200",
            lectureNumber = "001",
            courseTitle = "컴파일러",
            classTime = "화(10:00~11:15)",
            place = "301-102",
            instructor = "박교수",
            credit = 3,
        )

    @BeforeEach
    fun seed() {
        autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        clock.setKst(LocalDateTime.of(2026, 8, 3, 10, 0))
    }

    private class Member(
        val user: User,
        val timetable: Timetable,
    )

    private fun member(
        localId: String,
        vararg rows: RowData,
        language: Language = Language.KO,
    ): Member {
        val user = userWithDevice(localId, language)
        val timetable = fixtures.timetable(user, autumn)
        rows.forEach { addLecture(user, timetable, lecture(it)) }
        return Member(user, timetable)
    }

    private fun lecture(row: RowData): Lecture = lecture(row.courseNumber, row.lectureNumber)

    private fun Member.entryId(row: RowData): Long =
        timetableLectureRepository.findByTimetableIdIn(listOf(timetable.id!!)).single { it.lectureId == lecture(row).id }.id!!

    private fun Member.lectureIds() = timetableLectureIds(timetable)

    private fun Member.messages() = notificationsOf(user).map { it.message }

    private fun Member.timetableDeeplink(row: RowData) =
        deeplink("timetable-lecture", "timetableId=${timetable.id}&lectureId=${lecture(row).id}")

    private fun pushBodies(): Map<String, String> = sentPushes().associate { it.fcmRegistrationId to it.message.body }

    private fun updated(
        title: String,
        labels: String,
    ) = "2026-2학기 '나의 시간표' 시간표의 '$title' 강의가 업데이트 되었습니다.(항목: $labels)"

    // ---------------------------------------------------------------- 추가 · 폐강

    @Test
    fun `새로 열린 강의는 추가만 하고 아무에게도 알리지 않는다`() {
        sync(listOf(algo))
        member("alice", algo)
        clearPushes()
        // 지난 학기에 같은 강좌번호·교수로 열려 강의평 과목이 이미 있다.
        val course = courseRepository.save(Course(courseNumber = "400.300", instructor = "정교수", title = "데이터베이스(옛 이름)"))

        sync(
            listOf(
                algo,
                RowData(
                    courseNumber = "400.300",
                    lectureNumber = "001",
                    courseTitle = "데이터베이스",
                    instructor = "정교수",
                    classTime = "",
                    place = "",
                ),
            ),
        )

        val database = lecture("400.300")
        assertEquals(course.id, database.courseId)
        val refreshed = courseRepository.findById(course.id!!).get()
        assertEquals(database.id, refreshed.latestLectureId)
        assertEquals("데이터베이스", refreshed.title)
        // 수업교시가 빈 강의는 시간 없이 들어간다.
        assertEquals(emptyList<ClassPlaceAndTime>(), classTimes(database))
        assertTrue(lectureRepository.findVocabulary(2026, Semester.AUTUMN, Language.KO).instructor.contains("정교수"))
        assertEquals(0, notificationRepository.count())
        assertEquals(emptyMap<String, String>(), pushBodies())
    }

    @Test
    fun `폐강되면 담은 시간표에서 빼고 시간표·관심강좌 사용자에게 알린다`() {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo, compiler)
        val bob = userWithDevice("bob", Language.EN)
        val algoId = lecture(algo).id!!
        bookmarkService.addLecture(bob.id!!, algoId)
        vacancyNotificationRepository.save(VacancyNotification(userId = bob.id!!, lectureId = algoId))
        lectureRegistrationStatusRepository.save(LectureRegistrationStatus(lectureId = algoId, registrationCount = 10, wasFull = false))
        clearPushes()

        sync(listOf(compiler))

        assertNull(lectureOrNull("400.100"))
        assertEquals(listOf(lecture(compiler).id), alice.lectureIds())
        assertEquals(
            listOf(
                Triple(
                    NotificationType.LECTURE_REMOVE,
                    "2026-2학기 '나의 시간표' 시간표의 '알고리즘' 강의가 폐강되어 삭제되었습니다.",
                    deeplink("notifications"),
                ),
            ),
            notificationsOf(alice.user).map { Triple(it.type, it.message, it.deeplink) },
        )
        assertEquals(
            listOf(
                Triple(
                    NotificationType.LECTURE_REMOVE,
                    "2026 Fall · Bookmarks: \"알고리즘\" was cancelled and has been removed.",
                    deeplink("bookmarks", "year=2026&semester=AUTUMN&lectureId=$algoId"),
                ),
            ),
            notificationsOf(bob).map { Triple(it.type, it.messageEn, it.deeplink) },
        )
        // 푸시는 시간표에서 빠진 사용자에게만 간다. 관심강좌 알림은 알림함에만 남는다.
        assertEquals(mapOf("fcm-alice" to "강의 1개가 삭제되었습니다. 알림함에서 자세히 확인하세요."), pushBodies())
        // 관심강좌·빈자리 구독·수강신청 현황은 강의와 함께 지워진다(FK cascade).
        assertEquals(0, bookmarkLectureRepository.count())
        assertEquals(0, vacancyNotificationRepository.count())
        assertEquals(0, lectureRegistrationStatusRepository.count())
    }

    // ---------------------------------------------------------------- 시간 · 강의실

    @Test
    fun `수업 시간이 바뀌면 시간과 리마인더를 바꾸고 시간표·관심강좌 사용자에게 알린다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)
        setReminder(alice.user, alice.timetable, alice.entryId(algo), TimetableLectureReminderOption.ZERO_MINUTE)
        val bob = userWithDevice("bob")
        bookmarkService.addLecture(bob.id!!, lecture(algo).id!!)
        clearPushes()

        sync(listOf(algo.copy(classTime = "월(13:00~14:15)/수(13:00~14:15)", place = "301-101/301-101")))

        assertEquals(
            listOf(
                ClassPlaceAndTime(DayOfWeek.MONDAY, "301-101", 780, 855),
                ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "301-101", 780, 855),
            ),
            classTimes(lecture(algo)),
        )
        assertEquals(listOf(Schedule(DayOfWeek.MONDAY, 780), Schedule(DayOfWeek.WEDNESDAY, 780)), reminderSchedules(alice.entryId(algo)))
        assertEquals(
            listOf(updated("알고리즘", "강의 시간/장소") to alice.timetableDeeplink(algo)),
            notificationsOf(alice.user).map { it.message to it.deeplink },
        )
        assertEquals(
            listOf("2026-2학기 관심강좌 목록의 '알고리즘' 강의가 업데이트 되었습니다.(항목: 강의 시간/장소)"),
            notificationsOf(bob).map { it.message },
        )
        assertEquals(mapOf("fcm-alice" to "강의 1개가 변경되었습니다. 알림함에서 자세히 확인하세요."), pushBodies())
        assertEquals(clock.instant(), timetableRepository.findById(alice.timetable.id!!).get().updatedAt)
    }

    @Test
    fun `수업 시간이 없어지면 강의는 시간표에 남고 리마인더를 지운다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)
        setReminder(alice.user, alice.timetable, alice.entryId(algo), TimetableLectureReminderOption.ZERO_MINUTE)

        sync(listOf(algo.copy(classTime = "", place = "")))

        assertEquals(emptyList<ClassPlaceAndTime>(), classTimes(lecture(algo)))
        assertEquals(listOf(lecture(algo).id), alice.lectureIds())
        assertNull(reminderSchedules(alice.entryId(algo)))
        assertEquals(listOf(updated("알고리즘", "강의 시간/장소")), alice.messages())
    }

    @Test
    fun `강의실만 바뀌면 리마인더 시각과 최근 발송 기록은 그대로 두고 알린다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)
        setReminder(alice.user, alice.timetable, alice.entryId(algo), TimetableLectureReminderOption.TEN_MINUTES_AFTER)
        val notifiedAt = Instant.parse("2026-08-03T01:10:00Z")
        val reminder = reminderRepository.findByTimetableLectureId(alice.entryId(algo))!!
        reminderScheduleRepository.saveAll(
            reminderScheduleRepository.findByReminderId(reminder.id!!).onEach {
                it.recentNotifiedAt =
                    notifiedAt
            },
        )

        sync(listOf(algo.copy(place = "302-105")))

        assertEquals(listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "302-105", 600, 675)), classTimes(lecture(algo)))
        val schedules = reminderScheduleRepository.findByReminderId(reminder.id!!)
        assertEquals(listOf(Schedule(DayOfWeek.MONDAY, 610)), schedules.map { it.toSchedule() })
        // 같은 발송 시각이면 최근 발송 기록을 이어받아, 동기화 직후 같은 수업에 리마인더가 두 번 가지 않는다.
        assertEquals(listOf(notifiedAt), schedules.map { it.recentNotifiedAt })
        assertEquals(listOf(updated("알고리즘", "강의 시간/장소")), alice.messages())
    }

    // ---------------------------------------------------------------- 교수 · 정원 · 기타 항목

    @Test
    fun `교수가 바뀌면 강의평 과목을 새 교수로 잇고 알린다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)
        val oldCourseId = lecture(algo).courseId

        sync(listOf(algo.copy(instructor = "이교수", instructorEn = "Lee")))

        val lecture = lecture(algo)
        assertEquals("이교수" to "Lee", lecture.instructor to lecture.instructorEn)
        assertNotEquals(oldCourseId, lecture.courseId)
        assertEquals("400.100" to "이교수", courseRepository.findById(lecture.courseId!!).get().let { it.courseNumber to it.instructor })
        // 교수 영문명(영어 엑셀)도 바뀌지만 항목은 한 번만 적는다.
        assertEquals(listOf(updated("알고리즘", "교수")), alice.messages())
    }

    @Test
    fun `정원과 신입생 정원이 바뀌면 정원·기타 항목으로 알린다`() {
        sync(listOf(algo.copy(quotaText = "40 (30)")))
        val alice = member("alice", algo, language = Language.EN)
        assertEquals(10, lecture(algo).freshmanQuota)

        sync(listOf(algo.copy(quotaText = "50 (30)")))

        assertEquals(50 to 20, lecture(algo).let { it.quota to it.freshmanQuota })
        assertEquals(listOf(updated("알고리즘", "정원, 기타")), alice.messages())
        assertEquals(
            listOf("2026 Fall · \"나의 시간표\" timetable: \"알고리즘\" was updated. (changed: Quota, Other)"),
            notificationsOf(alice.user).map { it.messageEn },
        )
        assertEquals(mapOf("fcm-alice" to "1 lecture changed. Check your notifications for details."), pushBodies())
    }

    @Test
    fun `여러 항목이 함께 바뀌면 정해진 순서로 묶어 한 번 알린다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)

        sync(
            listOf(
                algo.copy(
                    classification = "전필",
                    academicYear = "4학년",
                    courseTitle = "알고리즘 설계",
                    courseTitleEn = "Algorithm Design",
                    credit = 4,
                    remark = "영어강의",
                ),
            ),
        )

        val lecture = lecture(algo)
        assertEquals(
            listOf("전필", "4학년", "알고리즘 설계", "영어강의"),
            listOf(lecture.classification, lecture.academicYear, lecture.courseTitle, lecture.remark),
        )
        assertEquals(4, lecture.credit)
        assertEquals(listOf(updated("알고리즘 설계", "교과 구분, 학년, 강의명, 학점, 비고")), alice.messages())
        assertEquals(
            listOf(
                "2026 Fall · \"나의 시간표\" timetable: \"Algorithm Design\" was updated. " +
                    "(changed: Classification, Academic year, Course title, Credits, Remarks)",
            ),
            notificationsOf(alice.user).map { it.messageEn },
        )
    }

    @Test
    fun `영어 필드만 바뀌면 강의는 고치되 알리지 않는다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)
        clearPushes()

        sync(
            listOf(algo),
            englishRows = listOf(algo.copy(courseTitleEn = "Algorithms", instructorEn = "Kim", classificationEn = "Major Elective")),
        )

        val lecture = lecture(algo)
        assertEquals(
            listOf("Algorithms", "Kim", "Major Elective"),
            listOf(lecture.courseTitleEn, lecture.instructorEn, lecture.classificationEn),
        )
        assertEquals(0, notificationRepository.count())
        assertEquals(emptyMap<String, String>(), pushBodies())
        assertEquals(listOf(lecture.id), alice.lectureIds())
    }

    // ---------------------------------------------------------------- 분반 · 강좌번호 재사용

    @Test
    fun `분반 번호가 바뀌면 기존 분반은 폐강, 새 분반은 추가로 처리한다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)
        val oldId = lecture(algo).id

        sync(listOf(algo.copy(lectureNumber = "002")))

        assertNull(lectureOrNull("400.100", "001"))
        assertNotEquals(oldId, lecture("400.100", "002").id)
        assertEquals(emptyList<Long?>(), alice.lectureIds())
        assertEquals(listOf("2026-2학기 '나의 시간표' 시간표의 '알고리즘' 강의가 폐강되어 삭제되었습니다."), alice.messages())
    }

    @Test
    fun `같은 강좌·분반 번호에 다른 강의가 오면 같은 강의의 변경으로 보고 시간표에 남긴다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)
        val id = lecture(algo).id

        sync(listOf(algo.copy(courseTitle = "알고리즘 특강", instructor = "최교수")))

        assertEquals(id, lecture(algo).id)
        assertEquals(listOf(id), alice.lectureIds())
        assertEquals(listOf(updated("알고리즘 특강", "강의명, 교수")), alice.messages())
    }

    @Test
    fun `폐강된 강좌번호가 다시 열리면 새 강의로 추가되고 시간표에 되살리지 않는다`() {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo)
        val oldId = lecture(algo).id
        sync(listOf(compiler))

        sync(listOf(algo, compiler))

        assertNotEquals(oldId, lecture(algo).id)
        assertEquals(emptyList<Long?>(), alice.lectureIds())
        assertEquals(listOf("2026-2학기 '나의 시간표' 시간표의 '알고리즘' 강의가 폐강되어 삭제되었습니다."), alice.messages())
    }

    // ---------------------------------------------------------------- 시간 겹침

    @Test
    fun `시간이 바뀌어 시간표의 다른 강의와 겹치면 그 시간표에서만 빼고 알린다`() {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo, compiler)
        setReminder(alice.user, alice.timetable, alice.entryId(algo), TimetableLectureReminderOption.ZERO_MINUTE)
        val aliceAlgoEntry = alice.entryId(algo)
        val bob = member("bob", algo)
        clearPushes()

        sync(listOf(algo.copy(classTime = "화(10:30~11:45)"), compiler))

        assertEquals(listOf(lecture(compiler).id), alice.lectureIds())
        assertNull(reminderRepository.findByTimetableLectureId(aliceAlgoEntry))
        assertEquals(
            listOf(
                Triple(
                    NotificationType.LECTURE_REMOVE,
                    "2026-2학기 '나의 시간표' 시간표의 '알고리즘' 강의가 업데이트되었으나, 시간표의 다른 강의와 겹쳐 삭제되었습니다.",
                    deeplink("notifications"),
                ),
            ),
            notificationsOf(alice.user).map { Triple(it.type, it.message, it.deeplink) },
        )
        assertEquals(listOf(lecture(algo).id), bob.lectureIds())
        assertEquals(listOf(updated("알고리즘", "강의 시간/장소")), bob.messages())
        assertEquals(
            mapOf(
                "fcm-alice" to "강의 1개가 삭제되었습니다. 알림함에서 자세히 확인하세요.",
                "fcm-bob" to "강의 1개가 변경되었습니다. 알림함에서 자세히 확인하세요.",
            ),
            pushBodies(),
        )
    }

    @Test
    fun `두 강의가 같은 시간으로 옮겨 오면 먼저 처리한 강의만 빼고 다른 강의는 남긴다`() {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo, compiler)
        clearPushes()

        sync(listOf(algo.copy(classTime = "수(10:00~11:15)"), compiler.copy(classTime = "수(10:00~11:15)")))

        assertEquals(listOf(lecture(compiler).id), alice.lectureIds())
        assertEquals(
            listOf(
                "2026-2학기 '나의 시간표' 시간표의 '알고리즘' 강의가 업데이트되었으나, 시간표의 다른 강의와 겹쳐 삭제되었습니다.",
                updated("컴파일러", "강의 시간/장소"),
            ),
            alice.messages(),
        )
        assertEquals(mapOf("fcm-alice" to "강의 1개가 변경, 1개가 삭제되었습니다. 알림함에서 자세히 확인하세요."), pushBodies())
    }

    @Test
    fun `두 강의가 시간을 맞바꾸면 바뀐 시간끼리 비교하므로 둘 다 남는다`() {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo, compiler)

        sync(listOf(algo.copy(classTime = compiler.classTime), compiler.copy(classTime = algo.classTime)))

        assertEquals(listOf(lecture(algo).id, lecture(compiler).id), alice.lectureIds())
        assertEquals(listOf(updated("알고리즘", "강의 시간/장소"), updated("컴파일러", "강의 시간/장소")), alice.messages())
    }

    @Test
    fun `사용자가 시간을 직접 고친 강의는 겹쳐도 빼지 않고 리마인더도 사용자 시간을 따른다`() {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo, compiler)
        timetableLectureService.modifyLecture(
            alice.user.id!!,
            alice.timetable.id!!,
            alice.entryId(algo),
            TimetableLectureModifyRequest(classPlaceAndTimes = listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "보충 강의실", 600, 675))),
        )
        setReminder(alice.user, alice.timetable, alice.entryId(algo), TimetableLectureReminderOption.ZERO_MINUTE)

        sync(listOf(algo.copy(classTime = compiler.classTime), compiler))

        assertEquals(listOf(lecture(algo).id, lecture(compiler).id), alice.lectureIds())
        assertEquals(listOf(Schedule(DayOfWeek.MONDAY, 600)), reminderSchedules(alice.entryId(algo)))
        assertEquals(listOf(updated("알고리즘", "강의 시간/장소")), alice.messages())
    }

    @Test
    fun `시간이 바뀌어 커스텀 강의와 겹쳐도 뺀다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)
        timetableLectureService.addCustomLecture(
            alice.user.id!!,
            alice.timetable.id!!,
            CustomTimetableLectureAddRequest(
                courseTitle = "동아리",
                classPlaceAndTimes = listOf(ClassPlaceAndTime(DayOfWeek.TUESDAY, "학생회관", 600, 720)),
            ),
        )

        sync(listOf(algo.copy(classTime = "화(11:00~12:15)")))

        assertEquals(listOf<Long?>(null), alice.lectureIds())
        assertEquals(
            listOf("2026-2학기 '나의 시간표' 시간표의 '알고리즘' 강의가 업데이트되었으나, 시간표의 다른 강의와 겹쳐 삭제되었습니다."),
            alice.messages(),
        )
    }

    @Test
    fun `커스텀 강의만 있는 시간표는 동기화의 영향을 받지 않는다`() {
        sync(listOf(algo))
        val carol = member("carol")
        timetableLectureService.addCustomLecture(
            carol.user.id!!,
            carol.timetable.id!!,
            CustomTimetableLectureAddRequest(
                courseTitle = "알고리즘",
                classPlaceAndTimes = listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "301-101", 600, 675)),
            ),
        )
        val before = timetableLectureRepository.findByTimetableIdIn(listOf(carol.timetable.id!!)).single()
        val updatedAt = timetableRepository.findById(carol.timetable.id!!).get().updatedAt
        clock.advance(Duration.ofDays(1))
        clearPushes()

        sync(listOf(algo.copy(classTime = "월(10:30~11:45)", instructor = "이교수")))

        val after = timetableLectureRepository.findByTimetableIdIn(listOf(carol.timetable.id!!)).single()
        assertEquals(before.id to before.overrides, after.id to after.overrides)
        assertEquals(updatedAt, timetableRepository.findById(carol.timetable.id!!).get().updatedAt)
        assertEquals(0, notificationRepository.count())
        assertEquals(emptyMap<String, String>(), pushBodies())
    }

    // ---------------------------------------------------------------- 멱등 · 부분 응답

    @Test
    fun `같은 데이터로 다시 동기화하면 강의·시간표·알림 어느 것도 바꾸지 않는다`() {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo, compiler)
        setReminder(alice.user, alice.timetable, alice.entryId(algo), TimetableLectureReminderOption.ZERO_MINUTE)

        fun snapshot() =
            listOf(
                lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN).map { listOf(it.id, it.updatedAt, it.courseId) },
                lectureClassTimeRepository.findAll().map { it.id },
                timetableRepository.findById(alice.timetable.id!!).get().updatedAt,
                reminderScheduleRepository.findAll().map { it.id },
                courseRepository.count(),
            )
        val before = snapshot()
        clock.advance(Duration.ofDays(1))
        clearPushes()

        sync(listOf(algo, compiler))

        assertEquals(before, snapshot())
        assertEquals(0, notificationRepository.count())
        assertEquals(emptyMap<String, String>(), pushBodies())
        // 동기화를 마쳤다는 표시로 학기 갱신 시각만 바뀐다.
        assertEquals(clock.instant(), coursebookRepository.findAll().single().updatedAt)
    }

    @Test
    fun `강좌 상세 API가 일부 강좌에 빈 응답이나 일부 필드만 주면 엑셀 값으로 채우고 강의를 지우지 않는다`() {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo, compiler)
        stubSite(listOf(algo, compiler))
        sugang.lectureInfo("400.100", "001", json("{}"))
        sugang.lectureInfo("400.200", "001", json("""{"ltTime":[],"ltRoom":[],"LISTTAB01":{"tlsnAplyCapaCnt":45}}"""))

        sugangSnuSyncJob.run(null)

        assertEquals(40, lecture(algo).quota)
        assertEquals(45, lecture(compiler).quota)
        assertEquals(listOf(ClassPlaceAndTime(DayOfWeek.TUESDAY, "301-102", 600, 675)), classTimes(lecture(compiler)))
        assertEquals(listOf(lecture(algo).id, lecture(compiler).id), alice.lectureIds())
        assertEquals(listOf(updated("컴파일러", "정원")), alice.messages())
    }

    // ---------------------------------------------------------------- 영어 엑셀 공백

    @Test
    fun `영어 엑셀이 비어 있으면 기존 영어 필드는 보존하고 신규 강의는 영어 없이 만든 뒤 다음 동기화에서 채운다`() {
        val english = algo.copy(courseTitleEn = "Algorithms", instructorEn = "Kim", classificationEn = "Major Elective")
        sync(listOf(algo), englishRows = listOf(english))
        val alice = member("alice", algo)
        clearPushes()

        // 학기 초처럼 한국어 엑셀만 공개되어 있다. 기존 강의의 한국어 값은 그대로, 신규 강의가 하나 있다.
        sync(listOf(algo, compiler), englishRows = emptyList())

        val kept = lecture(algo)
        assertEquals(listOf("Algorithms", "Kim", "Major Elective"), listOf(kept.courseTitleEn, kept.instructorEn, kept.classificationEn))
        val created = lecture(compiler)
        assertEquals(
            listOf<String?>(null, null, null, null, null, null, null),
            listOf(
                created.courseTitleEn,
                created.instructorEn,
                created.departmentEn,
                created.academicYearEn,
                created.categoryEn,
                created.classificationEn,
                created.remarkEn,
            ),
        )
        assertEquals(0, notificationRepository.count())

        // 영어 엑셀이 공개되면 신규 강의의 영어 필드가 채워진다. 영어 필드 변경은 알리지 않는다.
        sync(listOf(algo, compiler), englishRows = listOf(english, compiler.copy(courseTitleEn = "Compilers")))

        assertEquals("Compilers", lecture(compiler).courseTitleEn)
        assertEquals("Elective", lecture(compiler).classificationEn)
        assertEquals("Algorithms", lecture(algo).courseTitleEn)
        assertEquals(0, notificationRepository.count())
        assertEquals(emptyMap<String, String>(), pushBodies())
        assertEquals(listOf(kept.id), alice.lectureIds())
    }

    @Test
    fun `영어 엑셀이 비어 있어도 신규 학기를 만든다`() {
        stubSite(listOf(algo), englishRows = emptyList(), semester = Semester.WINTER)

        sugangSnuSyncJob.run(null)

        assertTrue(coursebookRepository.findAll().any { it.year == 2026 && it.semester == Semester.WINTER })
        val lecture = lecture("400.100", semester = Semester.WINTER)
        assertEquals("알고리즘", lecture.courseTitle)
        assertNull(lecture.courseTitleEn)
    }

    // ---------------------------------------------------------------- 학기 경계

    @Test
    fun `학기를 지정해 지난 학기를 다시 동기화하면 그 학기만 바꾸고 수강편람 조건·일정은 보지 않는다`() {
        sync(listOf(algo))
        fixtures.coursebook(2026, Semester.SPRING)
        stubSite(listOf(algo), semester = Semester.SPRING)
        sugangSnuSyncJob.run(YearAndSemester(2026, Semester.SPRING))
        val springAlgo = lecture("400.100", semester = Semester.SPRING)
        val alice = userWithDevice("alice")
        val springTable = fixtures.timetable(alice, Coursebook(2026, Semester.SPRING))
        addLecture(alice, springTable, springAlgo)
        val autumnBefore = lecture(algo).let { it.id to it.updatedAt }
        wireMock.resetRequests()

        stubSite(listOf(algo.copy(instructor = "이교수")), semester = Semester.SPRING)
        sugangSnuSyncJob.run(YearAndSemester(2026, Semester.SPRING))

        assertEquals("이교수", lectureRepository.findById(springAlgo.id!!).get().instructor)
        assertEquals("김교수", lecture(algo).instructor)
        assertEquals(autumnBefore, lecture(algo).let { it.id to it.updatedAt })
        assertEquals(listOf("2026-1학기 '나의 시간표' 시간표의 '알고리즘' 강의가 업데이트 되었습니다.(항목: 교수)"), notificationsOf(alice).map { it.message })
        assertEquals(0, sugang.requestsTo(CONDITION))
        assertEquals(0, sugang.requestsTo(MAIN))
        assertEquals(2, sugang.requestsTo(EXCEL))
        assertEquals(clock.instant(), coursebookRepository.findAll().single { it.semester == Semester.SPRING }.updatedAt)
    }

    @Test
    fun `겨울학기 다음에는 다음 해 1학기를 새로 연다`() {
        coursebookRepository.deleteAll()
        fixtures.coursebook(2026, Semester.WINTER)
        stubSite(listOf(algo), year = 2027, semester = Semester.SPRING)

        sugangSnuSyncJob.run(null)

        assertEquals(
            listOf(2026 to Semester.WINTER, 2027 to Semester.SPRING),
            coursebookRepository.findAll().map { it.year to it.semester }.sortedBy { it.first },
        )
        assertEquals("알고리즘", lecture("400.100", year = 2027, semester = Semester.SPRING).courseTitle)
    }

    @Test
    fun `수강편람 사이트가 최신 수강편람보다 이전 학기를 보여 주면 아무것도 받지 않고 중단한다`() {
        stubSite(listOf(algo), semester = Semester.SUMMER)

        val e = assertThrows<IllegalStateException> { sugangSnuSyncJob.run(null) }

        assertEquals("최신 수강편람(2026 AUTUMN)이 수강신청 사이트(2026 SUMMER)보다 최신이다", e.message)
        assertEquals(0, sugang.requestsTo(EXCEL))
        assertEquals(0, lectureRepository.count())
    }

    // ---------------------------------------------------------------- 트랜잭션 경계

    @Test
    fun `한 강의라도 저장에 실패하면 강의·강의평 과목·시간표·알림 반영을 모두 되돌린다`() {
        sync(listOf(algo))
        val alice = member("alice", algo)
        val syncedAt = coursebookRepository.findAll().single().updatedAt
        val courses = courseRepository.count()
        clock.advance(Duration.ofDays(1))
        clearPushes()

        // 강의실 컬럼(128자)보다 긴 값: 새 강의와 강의평 과목 INSERT, 기존 강의 변경·수업시간 삭제까지 마친 뒤
        // 마지막 수업시간 JDBC INSERT에서 실패한다. 그 앞의 JPA 변경까지 한 트랜잭션으로 되돌아가야 한다.
        val broken =
            RowData(
                courseNumber = "400.900",
                lectureNumber = "001",
                courseTitle = "새 강의",
                instructor = "신교수",
                classTime = "금(10:00~11:15)",
                place = "가".repeat(200),
            )
        assertThrows<DataAccessException> { sync(listOf(algo.copy(classTime = "화(10:00~11:15)", instructor = "이교수"), broken)) }

        assertEquals(listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "301-101", 600, 675)), classTimes(lecture(algo)))
        assertEquals("김교수", lecture(algo).instructor)
        assertNull(lectureOrNull("400.900"))
        assertEquals(courses, courseRepository.count())
        assertEquals(listOf(lecture(algo).id), alice.lectureIds())
        assertEquals(0, notificationRepository.count())
        assertEquals(emptyMap<String, String>(), pushBodies())
        assertEquals(syncedAt, coursebookRepository.findAll().single().updatedAt)
    }
}
