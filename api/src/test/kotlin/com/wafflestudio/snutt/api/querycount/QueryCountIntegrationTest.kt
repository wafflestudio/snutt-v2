package com.wafflestudio.snutt.api.querycount

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.scheduler.ReminderScheduler
import com.wafflestudio.snutt.api.testutil.legacyApiKey
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.domain.bookmark.model.BookmarkLecture
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkLectureRepository
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmission
import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmissionAnswer
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiarySubmissionAnswerRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiarySubmissionRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationLike
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationLikeRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.friend.model.Friend
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import com.wafflestudio.snutt.core.domain.theme.repository.TimetableThemeRepository
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminderSchedule
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderScheduleRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import com.wafflestudio.snutt.core.test.query.assertQueryCountStable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 주요 조회 엔드포인트의 요청당 SQL 수를 고정한다. 각 테스트는 응답에 들어갈 데이터를 늘리기 전과 후에 같은 요청을 보내
 * 쿼리 수가 같음(N+1 없음)과 그 값을 함께 검증한다. #60~#66에서 통합한 조회가 다시 쪼개지면 여기서 깨진다.
 *
 * 기대값을 바꿔야 하면 실패 메시지의 SQL 목록을 보고 늘어난 쿼리가 행마다 반복되는 것이 아닌지 먼저 확인한다.
 */
class QueryCountIntegrationTest : AbstractApiIntegrationTest() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var courseRepository: CourseRepository

    @Autowired lateinit var evaluationRepository: EvaluationRepository

    @Autowired lateinit var evaluationLikeRepository: EvaluationLikeRepository

    @Autowired lateinit var friendRepository: FriendRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var diaryQuestionRepository: DiaryQuestionRepository

    @Autowired lateinit var diarySubmissionRepository: DiarySubmissionRepository

    @Autowired lateinit var diarySubmissionAnswerRepository: DiarySubmissionAnswerRepository

    @Autowired lateinit var reminderRepository: TimetableLectureReminderRepository

    @Autowired lateinit var reminderScheduleRepository: TimetableLectureReminderScheduleRepository

    @Autowired lateinit var themeRepository: TimetableThemeRepository

    @Autowired lateinit var bookmarkRepository: BookmarkLectureRepository

    @Autowired lateinit var vacancyRepository: VacancyNotificationRepository

    @Autowired lateinit var reminderScheduler: ReminderScheduler

    private lateinit var autumn: Coursebook
    private lateinit var me: User

    /** 같은 사용자의 v2 access token과 v1 legacy token. */
    private lateinit var token: String
    private lateinit var legacyToken: String

    private var seq = 0

    @BeforeEach
    fun seedUser() {
        fixtures.coursebook(2025, Semester.AUTUMN)
        fixtures.coursebook(2026, Semester.SPRING)
        autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val registered =
            v1Send(HttpMethod.POST, "/v1/auth/register_local", """{"id":"qcuser","password":"password1","email":"qc@snu.ac.kr"}""")
        assertEquals(200, registered.statusCode.value(), registered.body)
        legacyToken = body(registered)["token"].asString()
        token = body(post("/v2/auth/login", """{"localId":"qcuser","password":"password1"}"""))["accessToken"].asString()
        // 강의평 조회는 이메일 인증 사용자만 할 수 있다.
        me = userRepository.save(userRepository.findByLocalIdAndActiveTrue("qcuser")!!.apply { isEmailVerified = true })
    }

    // ---- 시간표 ----

    @Test
    fun `시간표 목록은 시간표 수와 관계없이 쿼리 수가 같다`() {
        fillTimetable(fixtures.timetable(me, autumn, "첫 시간표", false), 2)
        val (before, after) =
            assertQueryCountStable(select = 6, grow = { repeat(3) { fillTimetable(fixtures.timetable(me, autumn, "추가$it", false), 2) } }) {
                body(get("/v2/timetables", token))
            }
        // v1 가입이 만든 기본 시간표 1개가 함께 나온다.
        assertEquals(2, before.size())
        assertEquals(5, after.size())
    }

    @Test
    fun `시간표 상세는 강의·수업시간·custom 강의 수와 관계없이 쿼리 수가 같다`() {
        val timetable = fixtures.timetable(me, autumn, "상세", false)
        fillTimetable(timetable, 1)
        val (before, after) =
            assertQueryCountStable(
                select = 6,
                grow = { fillTimetable(timetable, 4) },
            ) { body(get("/v2/timetables/${timetable.id}", token)) }
        assertEquals(1, before["lectures"].size())
        assertEquals(5, after["lectures"].size())
    }

    @Test
    fun `v1 시간표 목록과 상세도 강의 수와 관계없이 쿼리 수가 같다`() {
        val timetable = fixtures.timetable(me, autumn, "상세", false)
        fillTimetable(timetable, 1)
        val (before, after) =
            assertQueryCountStable(select = 8, grow = { fillTimetable(timetable, 4) }) { body(v1Get("/v1/tables/${timetable.id}")) }
        assertEquals(1, before["lecture_list"].size())
        assertEquals(5, after["lecture_list"].size())

        val (listBefore, listAfter) =
            assertQueryCountStable(
                select = 7,
                grow = { repeat(2) { fillTimetable(fixtures.timetable(me, autumn, "v1추가$it", false), 2) } },
            ) {
                body(v1Get("/v1/tables"))
            }
        assertEquals(2, listBefore.size())
        assertEquals(4, listAfter.size())
    }

    @Test
    fun `리마인더 목록은 리마인더 수와 관계없이 쿼리 수가 같다`() {
        val timetable = fixtures.timetable(me, autumn, "리마인더", false)
        fillTimetable(timetable, 1).forEach { setReminder(timetable, it) }
        val (before, after) =
            assertQueryCountStable(select = 3, grow = { fillTimetable(timetable, 3).forEach { setReminder(timetable, it) } }) {
                body(get("/v2/timetables/${timetable.id}/lectures/reminders", token))
            }
        assertEquals(1, before.size())
        assertEquals(4, after.size())
    }

    @Test
    fun `리마인더 발송은 대상 리마인더 수와 관계없이 쿼리 수가 같다`() {
        // 2026 가을학기 화요일 09:50 KST에 발화하는 리마인더.
        clock.setKst(LocalDateTime.of(2026, 9, 15, 9, 50))
        val now = clock.nowKst()
        val current = YearAndSemester(2026, Semester.AUTUMN)
        dueReminder(me)
        var fired = 0
        assertQueryCountStable(select = 3, insert = 1, update = 1, grow = { repeat(3) { dueReminder(fixtures.user()) } }) {
            reminderScheduler.fireDueReminders(now.withZoneSameInstant(ZoneId.of("Asia/Seoul")), current)
            fired++
        }
        assertEquals(2, fired)
        // 처음 1건, 두 번째에 새로 늘린 3건이 알림함에 쌓인다.
        assertEquals(4, notificationRepository.count())
    }

    // ---- 강의 검색 ----

    @Test
    fun `강의 검색은 결과 강의 수와 관계없이 쿼리 수가 같다`() {
        searchableLecture()
        val request = """{"year":2026,"semester":3,"query":"검색강의"}"""
        val (before, after) =
            assertQueryCountStable(
                select = 2,
                grow = { repeat(4) { searchableLecture() } },
            ) { body(post("/v2/lectures/search", request, token)) }
        assertEquals(1, before["content"].size())
        assertEquals(5, after["content"].size())
    }

    @Test
    fun `v1 강의 검색도 결과 강의 수와 관계없이 쿼리 수가 같다`() {
        searchableLecture()
        val request = """{"year":2026,"semester":3,"title":"검색강의"}"""
        val (before, after) =
            assertQueryCountStable(select = 3, grow = {
                repeat(4) { searchableLecture() }
            }) { body(v1Send(HttpMethod.POST, "/v1/search_query", request)) }
        assertEquals(1, before.size())
        assertEquals(5, after.size())
    }

    // ---- 강의평 ----

    @Test
    fun `강의평 목록과 요약은 강의평·공감 수와 관계없이 쿼리 수가 같다`() {
        val (course, _) = courseWithLecture()
        evaluationBy(course, fixtures.user())
        val grow = { repeat(4) { evaluationBy(course, fixtures.user(), likedBy = me) } }
        val (before, after) =
            assertQueryCountStable(select = 7, grow = grow) { body(get("/v2/courses/${course.id}/evaluations", token)) }
        assertEquals(1, before["content"].size())
        assertEquals(5, after["content"].size())

        assertQueryCountStable(select = 2, grow = { repeat(2) { evaluationBy(course, fixtures.user()) } }) {
            body(get("/v2/courses/${course.id}/evaluation-summary", token))
        }
    }

    @Test
    fun `내 강의평이 있으면 본인 강의평을 먼저 붙여도 쿼리 수가 같다`() {
        val (course, _) = courseWithLecture()
        evaluationBy(course, me)
        evaluationBy(course, fixtures.user())
        val (before, after) =
            assertQueryCountStable(select = 7, grow = { repeat(3) { evaluationBy(course, fixtures.user()) } }) {
                body(v1Get("/v1/ev-service/v1/lectures/${course.id}/evaluations?include_mine=true"))
            }
        assertEquals(2, before["content"].size())
        assertEquals(5, after["content"].size())
    }

    @Test
    fun `v1 강의평 요약도 강의평 수와 관계없이 쿼리 수가 같다`() {
        val (course, _) = courseWithLecture()
        evaluationBy(course, fixtures.user())
        assertQueryCountStable(select = 3, grow = { repeat(3) { evaluationBy(course, fixtures.user()) } }) {
            body(v1Get("/v1/ev-service/v1/lectures/${course.id}/evaluation-summary"))
        }
    }

    @Test
    fun `태그별·내 강의평 목록은 강의평이 여러 과목에 흩어져도 쿼리 수가 같다`() {
        evaluationBy(courseWithLecture().first, me)
        val grow = { repeat(3) { evaluationBy(courseWithLecture().first, me) } }
        val (before, after) = assertQueryCountStable(select = 4, grow = grow) { body(get("/v2/evaluations/tags/RECENT", token)) }
        assertEquals(1, before["content"].size())
        assertEquals(4, after["content"].size())

        val (mineBefore, mineAfter) =
            assertQueryCountStable(
                select = 5,
                grow = { evaluationBy(courseWithLecture().first, me) },
            ) { body(get("/v2/evaluations/me", token)) }
        assertEquals(4, mineBefore["content"].size())
        assertEquals(5, mineAfter["content"].size())
    }

    // ---- 수강 강의 ----

    @Test
    fun `수강 강의 조회는 지난 학기 강의 수와 관계없이 쿼리 수가 같다`() {
        val spring = Coursebook(year = 2026, semester = Semester.SPRING)
        val timetable = fixtures.timetable(me, spring)
        takenLecture(timetable, spring)
        val grow = { repeat(3) { takenLecture(timetable, spring) } }
        val (before, after) = assertQueryCountStable(select = 6, grow = grow) { body(get("/v2/users/me/lectures/latest", token)) }
        assertEquals(1, before.size())
        assertEquals(4, after.size())

        val (v1Before, v1After) =
            assertQueryCountStable(select = 8, grow = { takenLecture(timetable, spring) }) {
                body(v1Get("/v1/ev-service/v1/users/me/lectures/latest"))
            }
        assertEquals(4, v1Before["content"].size())
        assertEquals(5, v1After["content"].size())
    }

    // ---- 친구 ----

    @Test
    fun `친구 목록은 친구 수와 관계없이 쿼리 수가 같다`() {
        befriend(fixtures.user())
        val (before, after) =
            assertQueryCountStable(
                select = 3,
                grow = { repeat(4) { befriend(fixtures.user()) } },
            ) { body(get("/v2/friends?state=ACTIVE", token)) }
        assertEquals(1, before.size())
        assertEquals(5, after.size())

        val (v1Before, v1After) =
            assertQueryCountStable(select = 4, grow = { befriend(fixtures.user()) }) { body(v1Get("/v1/friends?state=active")) }
        assertEquals(5, v1Before["content"].size())
        assertEquals(6, v1After["content"].size())
    }

    @Test
    fun `친구 대표 시간표는 강의 수와 관계없이 쿼리 수가 같다`() {
        val friendUser = fixtures.user()
        val friend = befriend(friendUser)
        val timetable = fixtures.timetable(friendUser, autumn)
        fillTimetable(timetable, 1)
        val (before, after) =
            assertQueryCountStable(select = 7, grow = { fillTimetable(timetable, 3) }) {
                body(get("/v2/friends/${friend.id}/primary-table?year=2026&semester=AUTUMN", token))
            }
        assertEquals(1, before["lectures"].size())
        assertEquals(4, after["lectures"].size())
    }

    // ---- 알림 ----

    @Test
    fun `알림 목록은 알림 수와 관계없이 쿼리 수가 같다`() {
        notify(me.id)
        notify(null)
        val grow = { repeat(3) { notify(me.id) } }
        val (before, after) = assertQueryCountStable(select = 3, grow = grow) { body(get("/v2/notifications", token))["content"] }
        assertEquals(3, after.size() - before.size())

        val (v1Before, v1After) =
            assertQueryCountStable(select = 3, grow = { notify(null) }) { body(v1Get("/v1/notification")) }
        assertEquals(after.size(), v1Before.size())
        assertEquals(after.size() + 1, v1After.size())
    }

    // ---- 강의 일기장 ----

    @Test
    fun `내 강의 일기 목록은 제출·답변 수와 관계없이 쿼리 수가 같다`() {
        val questions =
            (1..3).map {
                diaryQuestionRepository.save(
                    DiaryQuestion(
                        question = "질문$it",
                        shortQuestion = "짧은질문$it",
                        answerList = listOf("그렇다", "아니다"),
                        shortAnswerList = listOf("O", "X"),
                    ),
                )
            }
        submitDiary(Semester.AUTUMN, questions.take(1))
        val grow = { listOf(Semester.AUTUMN, Semester.SPRING, Semester.SPRING).forEach { submitDiary(it, questions) } }
        val (before, after) = assertQueryCountStable(select = 4, grow = grow) { body(get("/v2/diaries/me", token)) }
        assertEquals(1, before.size())
        assertEquals(2, after.size())

        assertQueryCountStable(select = 5, grow = { submitDiary(Semester.AUTUMN, questions) }) { body(v1Get("/v1/diary/my")) }
    }

    // ---- #63·#64·#66에서 통합한 그 밖의 목록 ----

    @Test
    fun `테마 목록은 사용자 테마 수와 관계없이 쿼리 수가 같다`() {
        addTheme()
        val (before, after) = assertQueryCountStable(select = 5, grow = { repeat(3) { addTheme() } }) { body(get("/v2/themes", token)) }
        assertEquals(after.size() - 3, before.size())
    }

    @Test
    fun `관심강좌와 빈자리 알림 목록은 강의 수와 관계없이 쿼리 수가 같다`() {
        bookmarkAndWatch()
        val grow = { repeat(3) { bookmarkAndWatch() } }
        val (before, after) =
            assertQueryCountStable(
                select = 3,
                grow = grow,
            ) { body(get("/v2/bookmarks?year=2026&semester=AUTUMN", token)) }
        assertEquals(1, before["lectures"].size())
        assertEquals(4, after["lectures"].size())

        val (vBefore, vAfter) =
            assertQueryCountStable(select = 2, grow = {
                bookmarkAndWatch()
            }) { body(get("/v2/vacancy-notifications/lectures", token)) }
        assertEquals(4, vBefore["lectures"].size())
        assertEquals(5, vAfter["lectures"].size())

        val (v1Before, v1After) =
            assertQueryCountStable(select = 5, grow = { bookmarkAndWatch() }) { body(v1Get("/v1/bookmarks?year=2026&semester=3")) }
        assertEquals(5, v1Before["lectures"].size())
        assertEquals(6, v1After["lectures"].size())
    }

    // ---- 데이터 만들기 ----

    private fun next() = ++seq

    private fun times(n: Int) =
        listOf(
            ClassPlaceAndTime(DayOfWeek.entries[n % 5], "301-${100 + n}", 540 + (n % 6) * 90, 615 + (n % 6) * 90),
            ClassPlaceAndTime(DayOfWeek.entries[(n + 2) % 5], "302-${100 + n}", 540 + (n % 6) * 90, 615 + (n % 6) * 90),
        )

    private fun courseWithLecture(
        coursebook: Coursebook = autumn,
        title: String = "강의${next()}",
    ): Pair<Course, Lecture> {
        val n = next()
        val course = courseRepository.save(Course(courseNumber = "QC$n", instructor = "교수$n", title = title))
        val lecture =
            fixtures.lecture(coursebook, courseTitle = title, courseNumber = "QC$n", times = times(n)) {
                courseId = course.id
                instructor = "교수$n"
                classification = "전선"
                department = "컴퓨터공학부"
                quota = 40
            }
        return course to lecture
    }

    /** 시간표에 강의(과목·수업시간 2개 포함) [count]개를 넣고 timetable_lecture id를 돌려준다. */
    private fun fillTimetable(
        timetable: Timetable,
        count: Int,
    ): List<Long> =
        (1..count).map {
            val n = next()
            val lecture = courseWithLecture(Coursebook(year = timetable.year, semester = timetable.semester)).second
            fixtures.addLecture(timetable, lecture, paletteIndex = n % 9).id!!
        }

    private fun setReminder(
        timetable: Timetable,
        timetableLectureId: Long,
    ) {
        val response =
            put("/v2/timetables/${timetable.id}/lectures/$timetableLectureId/reminder", """{"option":"TEN_MINUTES_BEFORE"}""", token)
        assertEquals(200, response.statusCode.value(), response.body)
    }

    private fun dueReminder(user: User) {
        val timetable = fixtures.timetable(user, autumn, "리마인더${next()}")
        val timetableLectureId = fillTimetable(timetable, 1).single()
        val reminder = reminderRepository.save(TimetableLectureReminder(timetableLectureId = timetableLectureId, offsetMinutes = -10))
        reminderScheduleRepository.save(
            TimetableLectureReminderSchedule(
                reminderId = reminder.id!!,
                day = DayOfWeek.TUESDAY,
                minute =
                    9 * 60 + 50,
            ),
        )
    }

    private fun searchableLecture() {
        val (course, _) = courseWithLecture(title = "검색강의${next()}")
        evaluationBy(course, fixtures.user())
    }

    private fun evaluationBy(
        course: Course,
        author: User,
        likedBy: User? = null,
    ): Evaluation {
        val evaluation =
            evaluationRepository.save(
                Evaluation(
                    courseId = course.id!!,
                    userId = author.id,
                    year = 2026,
                    semester = Semester.SPRING,
                    content = "쿼리 수 검증용 강의평 본문입니다 ${next()}",
                    gradeSatisfaction = 4.0,
                    teachingSkill = 4.0,
                    gains = 4.0,
                    lifeBalance = 4.0,
                    rating = 4.0,
                ),
            )
        likedBy?.let { evaluationLikeRepository.save(EvaluationLike(evaluationId = evaluation.id!!, userId = it.id!!)) }
        return evaluation
    }

    private fun takenLecture(
        timetable: Timetable,
        coursebook: Coursebook,
    ) {
        val (course, lecture) = courseWithLecture(coursebook)
        fixtures.addLecture(timetable, lecture)
        evaluationBy(course, fixtures.user())
    }

    private fun befriend(other: User): Friend =
        friendRepository.save(Friend(fromUserId = other.id!!, toUserId = me.id!!, isAccepted = true))

    private fun notify(userId: Long?) {
        notificationRepository.save(
            Notification(userId = userId, title = "알림${next()}", message = "본문", type = NotificationType.NORMAL),
        )
    }

    private fun submitDiary(
        semester: Semester,
        questions: List<DiaryQuestion>,
    ) {
        val lecture = courseWithLecture(Coursebook(year = 2026, semester = semester)).second
        val submission =
            diarySubmissionRepository.save(
                DiarySubmission(
                    userId = me.id!!,
                    year = 2026,
                    semester = semester,
                    lectureId = lecture.id,
                    courseTitle = lecture.courseTitle,
                    comment = "일기",
                ),
            )
        questions.forEach {
            diarySubmissionAnswerRepository.save(
                DiarySubmissionAnswer(submissionId = submission.id!!, questionId = it.id!!, answerIndex = 0),
            )
        }
    }

    private fun addTheme() {
        themeRepository.save(TimetableTheme(me.id, name = "테마${next()}", colors = listOf(ColorSet("#FFFFFF", "#000000"))))
    }

    private fun bookmarkAndWatch() {
        val lecture = courseWithLecture().second
        bookmarkRepository.save(BookmarkLecture(userId = me.id!!, lectureId = lecture.id!!))
        vacancyRepository.save(VacancyNotification(userId = me.id!!, lectureId = lecture.id!!))
    }

    // ---- v1 호출 ----

    private fun v1Send(
        method: HttpMethod,
        uri: String,
        body: String? = null,
    ): ResponseEntity<String> {
        val spec =
            RestClient
                .builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }) { _, _ -> }
                .defaultHeader("x-access-apikey", legacyApiKey("ios", "0"))
                .defaultHeader("Content-Type", "application/json")
                .build()
                .method(method)
                .uri(uri)
        if (::legacyToken.isInitialized) spec.headers { it.set("x-access-token", legacyToken) }
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(String::class.java)
    }

    private fun v1Get(uri: String): ResponseEntity<String> = v1Send(HttpMethod.GET, uri)
}
