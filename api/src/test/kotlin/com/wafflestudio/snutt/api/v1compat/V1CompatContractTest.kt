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
                    department = "국어국문학과",
                    classification = "교양",
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
            .defaultHeader("x-access-apikey", "test-ios-key")
            .defaultHeader("Content-Type", "application/json")
            .build()

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
    fun `v1 시간표 상세는 레거시 형태를 유지한다`() {
        val add = post("/v1/tables", """{"year":2026,"semester":3,"title":"레거시시간표"}""", legacyToken)
        val timetableId = body(add)[0]["_id"].asString()

        val addLecture = post("/v1/tables/$timetableId/lecture/$lectureId", """{}""", legacyToken)
        assertEquals(200, addLecture.statusCode.value())

        val detail = get("/v1/tables/$timetableId", legacyToken)
        assertEquals(200, detail.statusCode.value())
        val node = body(detail)
        assertEquals(timetableId, node["id"].asString())
        assertEquals(userId, node["userId"].asString())
        val lectures = node["lectures"]
        assertEquals(1, lectures.size())
        val lecture = lectures[0]
        assertEquals("고급한국어", lecture["courseTitle"].asString())
        assertEquals(lectureId, lecture["lectureId"].asString())
        val classTimes = lecture["classPlaceAndTimes"]
        assertEquals(0, classTimes[0]["day"].asInt())
        assertEquals(570, classTimes[0]["startMinute"].asInt())
    }

    @Test
    fun `v1 검색은 레거시 형태를 반환한다`() {
        val search =
            post(
                "/v1/search_query",
                """{"year":2026,"semester":3,"title":"한국어"}""",
            )
        assertEquals(200, search.statusCode.value())
        val lectures = body(search)
        assertTrue(lectures.size() > 0)
        val lecture = lectures[0]
        assertEquals("고급한국어", lecture["course_title"].asString())
        assertTrue(lecture.has("_id"))
        assertTrue(lecture.has("class_time_json"))
        val classTimes = lecture["class_time_json"]
        assertEquals("09:30", classTimes[0]["start_time"].asString())
        // len은 교시 격자 길이: 09:30(1.5교시)~10:45(30분 올림→3교시) → 1.5
        assertEquals(1.5, classTimes[0]["len"].asDouble())
    }

    @Test
    fun `ev 경로는 ev 에러 봉투를 사용한다`() {
        val notVerified =
            post(
                "/v1/ev-service/v1/semester-lectures/$lectureId/evaluations",
                """{"content":"평가","gradeSatisfaction":4.0,"teachingSkill":4.0,"gains":4.0,"lifeBalance":4.0,"rating":4.0}""",
                legacyToken,
            )
        assertEquals(403, notVerified.statusCode.value())
        assertTrue(body(notVerified).has("errcode"))
    }
}
