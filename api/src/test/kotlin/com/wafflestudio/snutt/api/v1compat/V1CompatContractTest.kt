package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
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

/**
 * M6 DoD: v1 호환 레이어 계약 테스트 — 이중 매핑, x-access-token(credentialHash) 인증,
 * Deprecation 헤더, ev 에러 봉투 (PLAN.md §6)
 */
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

    @Autowired lateinit var lectureClassTimeRepository: com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Autowired
    lateinit var timetableRepository: com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository

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
                com.wafflestudio.snutt.core.domain.evaluation.model.Course(
                    courseNumber = "4190.777",
                    instructor = "호환교수",
                    title = "호환강의",
                    classification = "전선",
                ),
            )
        lectureId =
            saveLectureWithTimes(
                lectureRepository,
                lectureClassTimeRepository,
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "4190.777",
                    lectureNumber = "001",
                    courseTitle = "호환강의",
                    instructor = "호환교수",
                    courseId = course.id,
                ),
                listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "302-101", 570, 660)),
            ).externalId

        // v1 회원가입으로 credentialHash 토큰 발급 (모든 테스트가 공유)
        val register =
            post(
                "/v1/auth/register_local",
                """{"id":"v1user","password":"password1","email":"v1@snu.ac.kr"}""",
            )
        assertEquals(200, register.statusCode.value(), "body=${register.body}")
        legacyToken = asMap(register)["token"] as String
        userId = asMap(register)["user_id"] as String
        assertEquals("ok", asMap(register)["message"])
    }

    // 시간표는 테스트 간 공유되므로 각 테스트 전에 비운다
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

    @Suppress("UNCHECKED_CAST")
    private fun post(
        uri: String,
        body: String,
        legacyToken: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().post().uri(uri)
        legacyToken?.let { spec.headers { h -> h.set("x-access-token", it) } }
        return spec.body(body).retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun get(
        uri: String,
        legacyToken: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().get().uri(uri)
        legacyToken?.let { spec.headers { h -> h.set("x-access-token", it) } }
        return spec.retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(response: ResponseEntity<Any>): Map<String, Any?> = response.body as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun asList(response: ResponseEntity<Any>): List<Map<String, Any?>> = response.body as List<Map<String, Any?>>

    @Test
    fun `v1 로그인은 credentialHash 토큰을 발급한다`() {
        // @BeforeAll에서 발급한 토큰으로 v1 users/me 조회
        assertTrue(legacyToken.isNotBlank())
        val me = get("/v1/users/me", legacyToken)
        assertEquals(200, me.statusCode.value())
        assertEquals(userId, asMap(me)["id"])
        assertEquals("v1@snu.ac.kr", asMap(me)["email"])

        // v2 JWT는 v1 경로에서 거부된다
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
                .toEntity(Any::class.java)
        val v2Token = asMap(v2Login)["accessToken"] as String
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

        val briefs = asList(add)
        assertEquals(1, briefs.size)
        assertEquals("나의 시간표", briefs[0]["title"])
        assertEquals("2026", briefs[0]["year"].toString())

        // 경로는 /v1 접두사만 서빙한다
        assertEquals(404, post("/tables", """{"year":2026,"semester":3,"title":"루트"}""", legacyToken).statusCode.value())
    }

    @Test
    fun `v1 시간표 상세는 레거시 형태를 유지한다`() {
        val add = post("/v1/tables", """{"year":2026,"semester":3,"title":"레거시시간표"}""", legacyToken)
        val timetableId = asList(add)[0]["_id"] as String

        val addLecture = post("/v1/tables/$timetableId/lecture/$lectureId", """{}""", legacyToken)
        assertEquals(200, addLecture.statusCode.value())

        val detail = get("/v1/tables/$timetableId", legacyToken)
        assertEquals(200, detail.statusCode.value())
        val body = asMap(detail)
        assertEquals(timetableId, body["id"])
        assertEquals(userId, body["userId"])
        val lectures = body["lectures"] as List<*>
        assertEquals(1, lectures.size)
        val lecture = lectures[0] as Map<*, *>
        assertEquals("호환강의", lecture["courseTitle"])
        assertEquals(lectureId, lecture["lectureId"])
        // 시간표 응답의 classPlaceAndTimes는 단순 DTO다 (start_time/len은 검색 전용)
        val classTimes = lecture["classPlaceAndTimes"] as List<*>
        assertEquals(0, (classTimes[0] as Map<*, *>)["day"])
        assertEquals(570, (classTimes[0] as Map<*, *>)["startMinute"])
    }

    @Test
    fun `v1 검색은 레거시 형태를 반환한다`() {
        val search =
            post(
                "/v1/search_query",
                """{"year":2026,"semester":3,"title":"호환"}""",
            )
        assertEquals(200, search.statusCode.value())
        val lectures = asList(search)
        assertTrue(lectures.isNotEmpty())
        val lecture = lectures[0]
        assertEquals("호환강의", lecture["course_title"])
        assertTrue(lecture.containsKey("_id"))
        assertTrue(lecture.containsKey("class_time_json"))
        // 검색 LectureDto는 확장 시각 필드를 가진다
        val classTimes = lecture["class_time_json"] as List<*>
        assertEquals("09:30", (classTimes[0] as Map<*, *>)["start_time"])
        assertEquals(1.5, (classTimes[0] as Map<*, *>)["len"])
    }

    @Test
    fun `ev 경로는 ev 에러 봉투를 사용한다`() {
        // 이메일 미인증 → snutt 봉투 (proxy 게이트와 동일)
        val notVerified =
            post(
                "/v1/ev-service/v1/semester-lectures/$lectureId/evaluations",
                """{"content":"평가","gradeSatisfaction":4.0,"teachingSkill":4.0,"gains":4.0,"lifeBalance":4.0,"rating":4.0}""",
                legacyToken,
            )
        assertEquals(403, notVerified.statusCode.value())
        assertTrue(asMap(notVerified).containsKey("errcode"))
    }
}
