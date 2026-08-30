package com.wafflestudio.snutt.api.misc

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.clientconfig.repository.ClientConfigRepository
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.popup.repository.PopupRepository
import com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
class MiscDomainIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("misc_test") }
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
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var friendRepository: FriendRepository

    @Autowired
    lateinit var vacancyNotificationRepository: VacancyNotificationRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var popupRepository: PopupRepository

    @Autowired
    lateinit var configRepository: ClientConfigRepository

    @Autowired
    lateinit var pushPreferenceRepository: PushPreferenceRepository

    @LocalServerPort
    var port = 0

    private lateinit var userAToken: String
    private lateinit var userBToken: String
    private lateinit var adminToken: String
    private var lectureId: Long = 0L

    @BeforeAll
    fun seedDatabase() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        lectureId =
            saveLectureWithTimes(
                lectureRepository,
                lectureClassTimeRepository,
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "E43.101",
                    lectureNumber = "001",
                    courseTitle = "건강과 삶",
                    instructor = "김부석",
                    department = "체육교육과",
                    academicYear = "1학년",
                    category = "예술과 체육",
                    categoryPre2025 = "체육",
                    classification = "교양",
                    credit = 1,
                    quota = 30,
                ),
                listOf(ClassPlaceAndTime(DayOfWeek.THURSDAY, "71-1-214", 540, 590)),
            ).id!!

        userAToken = register("miscuserA", "misca@snu.ac.kr")
        userBToken = register("miscuserB", "miscb@snu.ac.kr")
        adminToken = register("miscadmin", "miscadmin@snu.ac.kr")
        userRepository.findByLocalIdAndActiveTrue("miscadmin")?.let { user ->
            user.isAdmin = true
            userRepository.save(user)
        }
    }

    @BeforeEach
    fun cleanDomainTables() {
        friendRepository.deleteAll()
        vacancyNotificationRepository.deleteAll()
        notificationRepository.deleteAll()
        popupRepository.deleteAll()
        configRepository.deleteAll()
        pushPreferenceRepository.deleteAll()
    }

    private fun register(
        localId: String,
        email: String,
    ): String {
        val response =
            post(
                "/v2/auth/register",
                """{"localId":"$localId","password":"password1","email":"$email"}""",
            )
        assertEquals(200, response.statusCode.value())
        return body(response)["accessToken"].asString()
    }

    private fun client(): RestClient =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }) { _, _ -> }
            .defaultHeader("x-client-platform", "ios")
            .defaultHeader("x-client-key", "test-ios-key")
            .defaultHeader("Content-Type", "application/json")
            .build()

    private fun post(
        uri: String,
        body: String,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().post().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.body(body).retrieve().toEntity(String::class.java)
    }

    private fun get(
        uri: String,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().get().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(String::class.java)
    }

    private fun patch(
        uri: String,
        body: String,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().patch().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.body(body).retrieve().toEntity(String::class.java)
    }

    private fun delete(
        uri: String,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().delete().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(String::class.java)
    }

    private val jsonMapper = JsonMapper.builder().build()

    private fun body(response: ResponseEntity<String>): JsonNode = jsonMapper.readTree(response.body!!)

    @Test
    fun `친구 요청 수락과 표시 이름`() {
        val request = post("/v2/friends", """{"nickname":"${getNickname("miscuserB")}"}""", userAToken)
        assertEquals(200, request.statusCode.value())

        val requested = body(get("/v2/friends?state=REQUESTED", userBToken))
        assertEquals(1, requested.size())
        val friendId = requested[0]["id"].asString()

        val duplicate = post("/v2/friends", """{"nickname":"${getNickname("miscuserB")}"}""", userAToken)
        assertEquals(409, duplicate.statusCode.value())

        assertEquals(200, post("/v2/friends/$friendId/accept", """{}""", userBToken).statusCode.value())

        val active = body(get("/v2/friends?state=ACTIVE", userAToken))
        assertEquals(1, active.size())
        assertFalse(active[0].hasNonNull("displayName"))

        assertEquals(200, patch("/v2/friends/$friendId/display-name", """{"displayName":"단짝"}""", userAToken).statusCode.value())
        val after = body(get("/v2/friends?state=ACTIVE", userAToken))
        assertEquals("단짝", after[0]["displayName"].asString())

        assertEquals(200, delete("/v2/friends/$friendId", userAToken).statusCode.value())
        assertEquals(0, body(get("/v2/friends?state=ACTIVE", userAToken)).size())
    }

    @Test
    fun `친구 초대 링크로 친구가 된다`() {
        val link = body(get("/v2/friends/generate-link", userAToken))
        val requestToken = link["requestToken"].asString()

        val accept = post("/v2/friends/accept-link/$requestToken", """{}""", userBToken)
        assertEquals(200, accept.statusCode.value())

        val duplicate = post("/v2/friends/accept-link/$requestToken", """{}""", userBToken)
        assertEquals(409, duplicate.statusCode.value())

        val invalid = post("/v2/friends/accept-link/invalidtoken", """{}""", userBToken)
        assertEquals(404, invalid.statusCode.value())
    }

    @Test
    fun `빈자리 알림 등록 조회 삭제`() {
        val add = post("/v2/vacancy-notifications/lectures/$lectureId", """{}""", userAToken)
        assertEquals(200, add.statusCode.value())

        val state = get("/v2/vacancy-notifications/lectures/$lectureId/state", userAToken)
        assertEquals(true, body(state).asBoolean())

        val lectures = body(get("/v2/vacancy-notifications/lectures", userAToken))
        val lectureList = lectures["lectures"]
        assertEquals(1, lectureList.size())
        assertEquals("건강과 삶", lectureList[0]["courseTitle"].asString())

        val remove = delete("/v2/vacancy-notifications/lectures/$lectureId", userAToken)
        assertEquals(200, remove.statusCode.value())
        assertEquals(0, body(get("/v2/vacancy-notifications/lectures", userAToken))["lectures"].size())
    }

    @Test
    fun `알림함 조회와 읽음 처리`() {
        val broadcast =
            post(
                "/v2/admin/notifications",
                """{"title":"전체공지","message":"안녕하세요","type":0}""",
                adminToken,
            )
        assertEquals(200, broadcast.statusCode.value())

        val notifications = body(get("/v2/notifications", userAToken))["content"]
        assertEquals(1, notifications.size())
        assertEquals("전체공지", notifications[0]["title"].asString())

        val count = body(get("/v2/notifications/count", userAToken))
        assertEquals(1, count["count"].asInt())

        body(get("/v2/notifications?explicit=1", userAToken))
        val after = body(get("/v2/notifications/count", userAToken))
        assertEquals(0, after["count"].asInt())
    }

    @Test
    fun `팝업과 클라이언트 설정`() {
        val popup =
            post(
                "/v2/admin/popups",
                """{"popupKey":"welcome2026","imageOriginUri":"https://cdn.example.com/welcome.png"}""",
                adminToken,
            )
        assertEquals(200, popup.statusCode.value())

        val popups = body(get("/v2/popups"))
        assertEquals(1, popups.size())
        assertEquals("https://cdn.example.com/welcome.png", popups[0]["imageUri"].asString())

        val config =
            post(
                "/v2/admin/configs/notice",
                """{"value":"{\"text\":\"공지\"}","minIosVersion":"3.0.0","maxIosVersion":"4.0.0"}""",
                adminToken,
            )
        assertEquals(200, config.statusCode.value())

        val adapted =
            client()
                .get()
                .uri("/v2/configs")
                .header("x-client-platform", "ios")
                .header("x-client-key", "test-ios-key")
                .header("x-app-version", "3.5.0")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, adapted.statusCode.value())
        val configs = body(adapted)
        assertTrue(configs.has("notice"))

        val outOfRange =
            client()
                .get()
                .uri("/v2/configs")
                .header("x-client-platform", "ios")
                .header("x-client-key", "test-ios-key")
                .header("x-app-version", "5.0.0")
                .retrieve()
                .toEntity(String::class.java)
        val outOfRangeNode = body(outOfRange)
        assertTrue(outOfRangeNode.isObject)
        assertEquals(0, outOfRangeNode.size())
    }

    @Test
    fun `푸시 프리퍼런스 저장과 조회`() {
        val saved =
            post(
                "/v2/push/preferences",
                """{"pushPreferences":[{"type":"LECTURE_UPDATE","isEnabled":false}]}""",
                userAToken,
            )
        assertEquals(200, saved.statusCode.value())
        val preferences = body(saved)["pushPreferences"]
        assertEquals(1, preferences.size())
        assertEquals("LECTURE_UPDATE", preferences[0]["type"].asString())
        assertEquals(false, preferences[0]["isEnabled"].asBoolean())
    }

    @Test
    fun `정적 페이지는 v2 정적 경로로 제공되고 구 루트 경로는 영구 리다이렉트된다`() {
        val member =
            client()
                .get()
                .uri("/v2/static/member")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, member.statusCode.value())
        assertTrue(member.body!!.contains("<html"))

        val legacy =
            client()
                .get()
                .uri("/member")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, legacy.statusCode.value())
        assertTrue(legacy.body!!.contains("<html"))
    }

    private fun getNickname(localId: String): String = userRepository.findByLocalIdAndActiveTrue(localId)!!.nickname
}
