package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
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
                .defaultHeader("x-client-platform", "ios")
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
}
