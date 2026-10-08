package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.testutil.legacyApiKey
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestionTarget
import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmission
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryDailyClassTypeRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionTargetRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiarySubmissionRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class V1CompatContractTest : AbstractApiIntegrationTest() {
    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var diaryDailyClassTypeRepository: DiaryDailyClassTypeRepository

    @Autowired
    lateinit var diaryQuestionRepository: DiaryQuestionRepository

    @Autowired
    lateinit var diaryQuestionTargetRepository: DiaryQuestionTargetRepository

    @Autowired
    lateinit var diarySubmissionRepository: DiarySubmissionRepository

    private lateinit var legacyToken: String
    private lateinit var userId: String
    private lateinit var lectureId: String

    @BeforeEach
    fun seed() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        val course = courseRepository.save(Course(courseNumber = "F27.301", instructor = "황현동", title = "고급한국어"))
        lectureId =
            saveLectureWithTimes(
                lectureRepository,
                lectureClassTimeRepository,
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "F27.301",
                    lectureNumber = "001",
                    courseTitle = "고급한국어",
                    instructor = "황현동",
                    department = "국어국문학과",
                    academicYear = "1학년",
                    category = "외국어",
                    classification = "교양",
                    credit = 3,
                    quota = 20,
                    courseId = course.id,
                ),
                listOf(
                    ClassPlaceAndTime(DayOfWeek.MONDAY, "3-106", 570, 645),
                    ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "3-106", 570, 645),
                ),
            ).id!!.toString()

        val registered = v1Post("/v1/auth/register_local", """{"id":"v1user","password":"password1","email":"v1@snu.ac.kr"}""")
        assertEquals(200, registered.statusCode.value(), "body=${registered.body}")
        legacyToken = body(registered)["token"].asString()
        userId = body(registered)["user_id"].asString()
        assertEquals("ok", body(registered)["message"].asString())
    }

    private fun v1Client(): RestClient =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }) { _, _ -> }
            .defaultHeader("x-access-apikey", legacyApiKey("ios", "0"))
            .defaultHeader("Content-Type", "application/json")
            .build()

    private fun v1Send(
        method: HttpMethod,
        uri: String,
        body: String?,
        legacyToken: String?,
    ): ResponseEntity<String> {
        val spec = v1Client().method(method).uri(uri)
        legacyToken?.let { spec.headers { h -> h.set("x-access-token", it) } }
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(String::class.java)
    }

    private fun v1Post(
        uri: String,
        body: String,
        legacyToken: String? = null,
    ): ResponseEntity<String> = v1Send(HttpMethod.POST, uri, body, legacyToken)

    private fun v1Get(
        uri: String,
        legacyToken: String? = null,
    ): ResponseEntity<String> = v1Send(HttpMethod.GET, uri, null, legacyToken)

    @Test
    fun `v1 로그인은 credentialHash 토큰을 발급한다`() {
        assertTrue(legacyToken.isNotBlank())
        val me = v1Get("/v1/users/me", legacyToken)
        assertEquals(200, me.statusCode.value())
        assertEquals(userId, body(me)["id"].asString())
        assertEquals("v1@snu.ac.kr", body(me)["email"].asString())

        val v2Login = post("/v2/auth/login", """{"localId":"v1user","password":"password1"}""")
        val v2Token = body(v2Login)["accessToken"].asString()
        val rejected = v1Get("/v1/users/me", v2Token)
        assertEquals(403, rejected.statusCode.value())
    }

    @Test
    fun `구 백엔드 apikey JWT로 v1 API를 호출할 수 있다`() {
        fun callWith(apiKey: String): ResponseEntity<String> =
            RestClient
                .builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }) { _, _ -> }
                .defaultHeader("x-access-apikey", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build()
                .post()
                .uri("/v1/auth/login_local")
                .body("""{"id":"v1user","password":"password1"}""")
                .retrieve()
                .toEntity(String::class.java)

        assertEquals(200, callWith(legacyApiKey("ios", "0")).statusCode.value())
        assertEquals(403, callWith(legacyApiKey("ios", "9")).statusCode.value())
    }

    @Test
    fun `v1 경로와 Deprecation 헤더`() {
        val add = v1Post("/v1/tables", """{"year":2026,"semester":3,"title":"테스트 시간표"}""", legacyToken)
        assertEquals(200, add.statusCode.value())
        assertTrue(add.headers.containsHeader("Deprecation"))
        assertEquals("true", add.headers.getFirst("Deprecation"))
        assertTrue(add.headers.containsHeader("Sunset"))
        assertTrue(add.headers.getFirst("Link")!!.contains("successor-version"))

        val brief = body(add).first { it["title"].asString() == "테스트 시간표" }
        assertEquals("2026", brief["year"].asString())

        assertEquals(404, v1Post("/tables", """{"year":2026,"semester":3,"title":"루트"}""", legacyToken).statusCode.value())
    }

    @Test
    fun `v1 파라미터가 잘못되면 레거시 오류 형식으로 400을 응답한다`() {
        val invalidSemester = v1Get("/v1/bookmarks?year=2026&semester=9", legacyToken)
        assertEquals(400, invalidSemester.statusCode.value())
        assertEquals(40001L, body(invalidSemester)["errcode"].asLong())
        assertTrue(body(invalidSemester).has("message"))
    }

    @Test
    fun `ev 경로는 ev 에러 형식으로 응답한다`() {
        val notVerified =
            v1Post(
                "/v1/ev-service/v1/semester-lectures/$lectureId/evaluations",
                """{"content":"평가","grade_satisfaction":4.0,"teaching_skill":4.0,"gains":4.0,"life_balance":4.0,"rating":4.0}""",
                legacyToken,
            )
        assertEquals(403, notVerified.statusCode.value())
        assertTrue(body(notVerified).has("errcode"))
    }

    @Test
    fun `iOS 요청 필드명으로 북마크를 추가하고 삭제한다`() {
        val added = v1Send(HttpMethod.POST, "/v1/bookmarks/lecture", """{"lecture_id":"$lectureId"}""", legacyToken)
        assertEquals(200, added.statusCode.value(), "body=${added.body}")
        assertEquals(lectureId, body(v1Get("/v1/bookmarks?year=2026&semester=3", legacyToken))["lectures"][0]["_id"].asString())

        val removed = v1Send(HttpMethod.DELETE, "/v1/bookmarks/lecture", """{"lecture_id":"$lectureId"}""", legacyToken)
        assertEquals(200, removed.statusCode.value(), "body=${removed.body}")
        assertTrue(body(v1Get("/v1/bookmarks?year=2026&semester=3", legacyToken))["lectures"].isEmpty)
    }

    @Test
    fun `iOS 요청 필드명으로 비밀번호를 변경한다`() {
        val register = v1Post("/v1/auth/register_local", """{"id":"v1pwuser","password":"password1","email":"v1pw@snu.ac.kr"}""")
        val changed =
            v1Send(
                HttpMethod.PUT,
                "/v1/user/password",
                """{"old_password":"password1","new_password":"password2"}""",
                body(register)["token"].asString(),
            )
        assertEquals(200, changed.statusCode.value(), "body=${changed.body}")
        assertEquals(200, v1Post("/v1/auth/login_local", """{"id":"v1pwuser","password":"password2"}""").statusCode.value())
    }

    @Test
    fun `iOS 요청 필드명으로 직접 만든 강의의 시간을 추가하고 수정한다`() {
        val timetableId =
            body(v1Post("/v1/tables", """{"year":2026,"semester":3,"title":"커스텀"}""", legacyToken))[0]["_id"].asString()
        val added =
            v1Post(
                "/v1/tables/$timetableId/lecture",
                """{"course_title":"자율학습","class_time_json":[{"day":0,"place":"301-101","startMinute":600,"endMinute":660}]}""",
                legacyToken,
            )
        assertEquals(200, added.statusCode.value(), "body=${added.body}")
        val lecture = body(added)["lecture_list"][0]
        assertEquals(600, lecture["class_time_json"][0]["startMinute"].asInt())

        val modified =
            v1Send(
                HttpMethod.PUT,
                "/v1/tables/$timetableId/lecture/${lecture["_id"].asString()}",
                """{"class_time_json":[{"day":1,"place":"301-101","startMinute":720,"endMinute":780}]}""",
                legacyToken,
            )
        assertEquals(200, modified.statusCode.value(), "body=${modified.body}")
        assertEquals(720, body(modified)["lecture_list"][0]["class_time_json"][0]["startMinute"].asInt())
    }

    @Test
    fun `알림 목록은 _id를 문자열로 응답한다`() {
        notificationRepository.save(Notification(userId = userId.toLong(), title = "공지", message = "내용"))

        val notification = body(v1Get("/v1/notification", legacyToken))[0]

        assertTrue(notification["_id"].isString)
    }

    @Test
    fun `강의 일기장 질문의 id는 문자열로 응답한다`() {
        val classType = diaryDailyClassTypeRepository.save(DiaryDailyClassType(name = "수업듣기"))
        val question =
            diaryQuestionRepository.save(
                DiaryQuestion(question = "어땠나요?", shortQuestion = "어땠나", answerList = listOf("좋음"), shortAnswerList = listOf("좋")),
            )
        diaryQuestionTargetRepository.save(DiaryQuestionTarget(questionId = question.id!!, dailyClassTypeId = classType.id!!))
        v1Post("/v1/tables", """{"year":2026,"semester":3,"title":"대표"}""", legacyToken)

        val questionnaire =
            v1Post("/v1/diary/questionnaire", """{"lectureId":$lectureId,"dailyClassTypes":["수업듣기"]}""", legacyToken)

        assertEquals(200, questionnaire.statusCode.value(), "body=${questionnaire.body}")
        assertEquals(question.id!!.toString(), body(questionnaire)["questions"][0]["id"].asString())
        assertTrue(body(questionnaire)["questions"][0]["id"].isString)
    }

    @Test
    fun `강의가 없는 학기의 태그 목록은 404로 응답한다`() {
        assertTrue(body(v1Get("/v1/tags/2026/3", legacyToken))["updated_at"].isNumber)
        assertEquals(404, v1Get("/v1/tags/2020/2", legacyToken).statusCode.value())
    }

    @Test
    fun `Android 3_12_4 이하에는 강의 일기장 기록 날짜를 오프셋 없이 응답한다`() {
        val submission =
            diarySubmissionRepository.save(
                DiarySubmission(
                    userId = userId.toLong(),
                    year = 2026,
                    semester = Semester.AUTUMN,
                    lectureId = lectureId.toLong(),
                    courseTitle = "고급한국어",
                    comment = "",
                ),
            )
        val createdAt = checkNotNull(submission.createdAt)

        fun diaryDate(
            osType: String,
            appVersion: String,
        ): String =
            v1Client()
                .get()
                .uri("/v1/diary/my")
                .headers {
                    it.set("x-access-token", legacyToken)
                    it.set("x-os-type", osType)
                    it.set("x-app-version", appVersion)
                }.retrieve()
                .toEntity(String::class.java)
                .let { body(it)[0]["submissions"][0]["date"].asString() }

        val legacy = diaryDate("android", "3.12.4")
        assertEquals(createdAt, LocalDateTime.parse(legacy).toInstant(ZoneOffset.UTC))
        assertEquals(createdAt, Instant.parse(diaryDate("android", "3.13.0")))
        assertEquals(createdAt, Instant.parse(diaryDate("ios", "3.12.4")))
    }
}
