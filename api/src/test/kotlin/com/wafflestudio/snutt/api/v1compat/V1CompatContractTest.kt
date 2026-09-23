package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestionTarget
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryDailyClassTypeRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionTargetRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import javax.crypto.spec.SecretKeySpec

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V1CompatContractTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("v1compat_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Autowired
    lateinit var timetableRepository: TimetableRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var diaryDailyClassTypeRepository: DiaryDailyClassTypeRepository

    @Autowired
    lateinit var diaryQuestionRepository: DiaryQuestionRepository

    @Autowired
    lateinit var diaryQuestionTargetRepository: DiaryQuestionTargetRepository

    @LocalServerPort
    var port = 0

    private lateinit var legacyToken: String
    private lateinit var userId: String
    private lateinit var lectureId: String

    @BeforeAll
    fun seedDatabase() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        val course =
            courseRepository.save(
                Course(
                    courseNumber = "F27.301",
                    instructor = "황현동",
                    title = "고급한국어",
                ),
            )
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

        val register =
            post(
                "/v1/auth/register_local",
                """{"id":"v1user","password":"password1","email":"v1@snu.ac.kr"}""",
            )
        assertEquals(200, register.statusCode.value(), "body=${register.body}")
        legacyToken = body(register)["token"].asString()
        userId = body(register)["user_id"].asString()
        assertEquals("ok", body(register)["message"].asString())
    }

    @BeforeEach
    fun cleanTimetables() {
        timetableRepository.deleteAll()
    }

    private fun client(): RestClient =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }) { _, _ -> }
            .defaultHeader("x-access-apikey", legacyApiKey("ios", "0"))
            .defaultHeader("Content-Type", "application/json")
            .build()

    private fun legacyApiKey(
        platform: String,
        keyVersion: String,
    ): String =
        Jwts
            .builder()
            .claim("string", platform)
            .claim("key_version", keyVersion)
            .signWith(
                SecretKeySpec("test-legacy-secret-key-0123456789abcdef".toByteArray(), "HmacSHA256"),
                Jwts.SIG.HS256,
            ).compact()

    private fun post(
        uri: String,
        body: String,
        legacyToken: String? = null,
    ): ResponseEntity<String> {
        val spec = client().post().uri(uri)
        legacyToken?.let { spec.headers { h -> h.set("x-access-token", it) } }
        return spec.body(body).retrieve().toEntity(String::class.java)
    }

    private fun send(
        method: HttpMethod,
        uri: String,
        body: String,
        legacyToken: String,
    ): ResponseEntity<String> =
        client()
            .method(method)
            .uri(uri)
            .headers { it.set("x-access-token", legacyToken) }
            .body(body)
            .retrieve()
            .toEntity(String::class.java)

    private fun get(
        uri: String,
        legacyToken: String? = null,
    ): ResponseEntity<String> {
        val spec = client().get().uri(uri)
        legacyToken?.let { spec.headers { h -> h.set("x-access-token", it) } }
        return spec.retrieve().toEntity(String::class.java)
    }

    private val jsonMapper = JsonMapper.builder().build()

    private fun body(response: ResponseEntity<String>): JsonNode = jsonMapper.readTree(response.body!!)

    @Test
    fun `v1 로그인은 credentialHash 토큰을 발급한다`() {
        assertTrue(legacyToken.isNotBlank())
        val me = get("/v1/users/me", legacyToken)
        assertEquals(200, me.statusCode.value())
        assertEquals(userId, body(me)["id"].asString())
        assertEquals("v1@snu.ac.kr", body(me)["email"].asString())

        val v2Login =
            RestClient
                .builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }) { _, _ -> }
                .defaultHeader("x-os-type", "ios")
                .defaultHeader("x-client-key", "test-ios-key")
                .defaultHeader("Content-Type", "application/json")
                .build()
                .post()
                .uri("/v2/auth/login")
                .body("""{"localId":"v1user","password":"password1"}""")
                .retrieve()
                .toEntity(String::class.java)
        val v2Token = body(v2Login)["accessToken"].asString()
        val rejected = get("/v1/users/me", v2Token)
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
        val add = post("/v1/tables", """{"year":2026,"semester":3,"title":"나의 시간표"}""", legacyToken)
        assertEquals(200, add.statusCode.value())
        assertTrue(add.headers.containsHeader("Deprecation"))
        assertEquals("true", add.headers.getFirst("Deprecation"))
        assertTrue(add.headers.containsHeader("Sunset"))
        assertTrue(add.headers.getFirst("Link")!!.contains("successor-version"))

        val briefs = body(add)
        assertEquals(1, briefs.size())
        assertEquals("나의 시간표", briefs[0]["title"].asString())
        assertEquals("2026", briefs[0]["year"].asString())

        assertEquals(404, post("/tables", """{"year":2026,"semester":3,"title":"루트"}""", legacyToken).statusCode.value())
    }

    @Test
    fun `v1 파라미터가 잘못되면 레거시 오류 형식으로 400을 응답한다`() {
        val invalidSemester = get("/v1/bookmarks?year=2026&semester=9", legacyToken)
        assertEquals(400, invalidSemester.statusCode.value())
        assertEquals(40001L, body(invalidSemester)["errcode"].asLong())
        assertTrue(body(invalidSemester).has("message"))
    }

    @Test
    fun `ev 경로는 ev 에러 형식으로 응답한다`() {
        val notVerified =
            post(
                "/v1/ev-service/v1/semester-lectures/$lectureId/evaluations",
                """{"content":"평가","grade_satisfaction":4.0,"teaching_skill":4.0,"gains":4.0,"life_balance":4.0,"rating":4.0}""",
                legacyToken,
            )
        assertEquals(403, notVerified.statusCode.value())
        assertTrue(body(notVerified).has("errcode"))
    }

    @Test
    fun `iOS 요청 필드명으로 북마크를 추가하고 삭제한다`() {
        val added = send(HttpMethod.POST, "/v1/bookmarks/lecture", """{"lecture_id":"$lectureId"}""", legacyToken)
        assertEquals(200, added.statusCode.value(), "body=${added.body}")
        assertEquals(lectureId, body(get("/v1/bookmarks?year=2026&semester=3", legacyToken))["lectures"][0]["_id"].asString())

        val removed = send(HttpMethod.DELETE, "/v1/bookmarks/lecture", """{"lecture_id":"$lectureId"}""", legacyToken)
        assertEquals(200, removed.statusCode.value(), "body=${removed.body}")
        assertTrue(body(get("/v1/bookmarks?year=2026&semester=3", legacyToken))["lectures"].isEmpty)
    }

    @Test
    fun `iOS 요청 필드명으로 비밀번호를 변경한다`() {
        val register = post("/v1/auth/register_local", """{"id":"v1pwuser","password":"password1","email":"v1pw@snu.ac.kr"}""")
        val changed =
            send(
                HttpMethod.PUT,
                "/v1/user/password",
                """{"old_password":"password1","new_password":"password2"}""",
                body(register)["token"].asString(),
            )
        assertEquals(200, changed.statusCode.value(), "body=${changed.body}")
        assertEquals(200, post("/v1/auth/login_local", """{"id":"v1pwuser","password":"password2"}""").statusCode.value())
    }

    @Test
    fun `iOS 요청 필드명으로 직접 만든 강의의 시간을 추가하고 수정한다`() {
        val timetableId =
            body(post("/v1/tables", """{"year":2026,"semester":3,"title":"커스텀"}""", legacyToken))[0]["_id"].asString()
        val added =
            post(
                "/v1/tables/$timetableId/lecture",
                """{"course_title":"자율학습","class_time_json":[{"day":0,"place":"301-101","startMinute":600,"endMinute":660}]}""",
                legacyToken,
            )
        assertEquals(200, added.statusCode.value(), "body=${added.body}")
        val lecture = body(added)["lecture_list"][0]
        assertEquals(600, lecture["class_time_json"][0]["startMinute"].asInt())

        val modified =
            send(
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

        val notification = body(get("/v1/notification", legacyToken))[0]

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
        post("/v1/tables", """{"year":2026,"semester":3,"title":"대표"}""", legacyToken)

        val questionnaire =
            post("/v1/diary/questionnaire", """{"lectureId":$lectureId,"dailyClassTypes":["수업듣기"]}""", legacyToken)

        assertEquals(200, questionnaire.statusCode.value(), "body=${questionnaire.body}")
        assertEquals(question.id!!.toString(), body(questionnaire)["questions"][0]["id"].asString())
        assertTrue(body(questionnaire)["questions"][0]["id"].isString)
    }

    @Test
    fun `강의가 없는 학기의 태그 목록은 404로 응답한다`() {
        assertTrue(body(get("/v1/tags/2026/3", legacyToken))["updated_at"].isNumber)
        assertEquals(404, get("/v1/tags/2020/2", legacyToken).statusCode.value())
    }
}
