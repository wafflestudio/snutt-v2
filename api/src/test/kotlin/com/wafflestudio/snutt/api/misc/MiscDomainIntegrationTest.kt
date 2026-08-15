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
 * M5a DoD: 친구(+초대 링크), 빈자리 알림, 알림함, 팝업, 클라이언트 설정, 푸시 프리퍼런스,
 * 정적 페이지, 어드민
 */
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

        // 친구 초대 링크 토큰 저장용 (v1 Redis 시맨틱)
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
    private lateinit var lectureId: String

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
            ).externalId

        userAToken = register("miscuserA", "misca@snu.ac.kr")
        userBToken = register("miscuserB", "miscb@snu.ac.kr")
        adminToken = register("miscadmin", "miscadmin@snu.ac.kr")
        userRepository.findByLocalIdAndActiveTrue("miscadmin")?.let { user ->
            user.isAdmin = true
            userRepository.save(user)
        }
    }

    // 테스트 간 데이터가 섞이지 않도록 각 테스트 전에 비운다
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
        return asMap(response)["accessToken"] as String
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

    @Suppress("UNCHECKED_CAST")
    private fun post(
        uri: String,
        body: String,
        token: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().post().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.body(body).retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun get(
        uri: String,
        token: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().get().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun patch(
        uri: String,
        body: String,
        token: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().patch().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.body(body).retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun delete(
        uri: String,
        token: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().delete().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(response: ResponseEntity<Any>): Map<String, Any?> = response.body as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun asList(response: ResponseEntity<Any>): List<Map<String, Any?>> = response.body as List<Map<String, Any?>>

    @Test
    fun `친구 요청 수락과 표시 이름`() {
        // A가 B에게 요청
        val request = post("/v2/friends", """{"nickname":"${getNickname("miscuserB")}"}""", userAToken)
        assertEquals(200, request.statusCode.value())

        // B의 받은 요청 목록
        val requested = asList(get("/v2/friends?state=REQUESTED", userBToken))
        assertEquals(1, requested.size)
        val friendId = requested[0]["id"] as String

        // 중복 요청
        val duplicate = post("/v2/friends", """{"nickname":"${getNickname("miscuserB")}"}""", userAToken)
        assertEquals(409, duplicate.statusCode.value())

        // B가 수락
        assertEquals(200, post("/v2/friends/$friendId/accept", """{}""", userBToken).statusCode.value())

        // A의 ACTIVE 목록
        val active = asList(get("/v2/friends?state=ACTIVE", userAToken))
        assertEquals(1, active.size)
        assertEquals(null, active[0]["displayName"])

        // 표시 이름 설정
        assertEquals(200, patch("/v2/friends/$friendId/display-name", """{"displayName":"단짝"}""", userAToken).statusCode.value())
        val after = asList(get("/v2/friends?state=ACTIVE", userAToken))
        assertEquals("단짝", after[0]["displayName"])

        // 친구 관계 끊기
        assertEquals(200, delete("/v2/friends/$friendId", userAToken).statusCode.value())
        assertEquals(0, asList(get("/v2/friends?state=ACTIVE", userAToken)).size)
    }

    @Test
    fun `친구 초대 링크로 친구가 된다`() {
        val link = asMap(get("/v2/friends/generate-link", userAToken))
        val requestToken = link["requestToken"] as String

        val accept = post("/v2/friends/accept-link/$requestToken", """{}""", userBToken)
        assertEquals(200, accept.statusCode.value())

        // 링크 재사용은 중복으로 거부된다
        val duplicate = post("/v2/friends/accept-link/$requestToken", """{}""", userBToken)
        assertEquals(409, duplicate.statusCode.value())

        // 유효하지 않은 토큰
        val invalid = post("/v2/friends/accept-link/invalidtoken", """{}""", userBToken)
        assertEquals(404, invalid.statusCode.value())
    }

    @Test
    fun `빈자리 알림 등록 조회 삭제`() {
        val add = post("/v2/vacancy-notifications/lectures/$lectureId", """{}""", userAToken)
        assertEquals(200, add.statusCode.value())

        val state = get("/v2/vacancy-notifications/lectures/$lectureId/state", userAToken)
        assertEquals(true, state.body)

        val lectures = asMap(get("/v2/vacancy-notifications/lectures", userAToken))
        val lectureList = lectures["lectures"] as List<*>
        assertEquals(1, lectureList.size)
        assertEquals("건강과 삶", (lectureList[0] as Map<*, *>)["courseTitle"])

        val remove = delete("/v2/vacancy-notifications/lectures/$lectureId", userAToken)
        assertEquals(200, remove.statusCode.value())
        assertEquals(0, (asMap(get("/v2/vacancy-notifications/lectures", userAToken))["lectures"] as List<*>).size)
    }

    @Test
    fun `알림함 조회와 읽음 처리`() {
        // 어드민이 전체 공지 + 개인 알림 등록
        val broadcast =
            post(
                "/v2/admin/notifications",
                """{"title":"전체공지","message":"안녕하세요","type":0}""",
                adminToken,
            )
        assertEquals(200, broadcast.statusCode.value())

        val notifications = asList(get("/v2/notifications", userAToken))
        assertEquals(1, notifications.size)
        assertEquals("전체공지", notifications[0]["title"])

        val count = asMap(get("/v2/notifications/count", userAToken))
        assertEquals(1, (count["count"] as Int).toLong())

        // explicit 읽음 처리
        asList(get("/v2/notifications?explicit=1", userAToken))
        val after = asMap(get("/v2/notifications/count", userAToken))
        assertEquals(0, (after["count"] as Int).toLong())
    }

    @Test
    fun `팝업과 클라이언트 설정`() {
        // 어드민 팝업 등록
        val popup =
            post(
                "/v2/admin/popups",
                """{"popupKey":"welcome2026","imageOriginUri":"https://cdn.example.com/welcome.png"}""",
                adminToken,
            )
        assertEquals(200, popup.statusCode.value())

        val popups = asList(get("/v2/popups"))
        assertEquals(1, popups.size)
        assertEquals("https://cdn.example.com/welcome.png", popups[0]["imageUri"])

        // 어드민 설정 등록 (ios 3.0~4.0 한정)
        val config =
            post(
                "/v2/admin/configs/notice",
                """{"value":"{\"text\":\"공지\"}","minIosVersion":"3.0.0","maxIosVersion":"4.0.0"}""",
                adminToken,
            )
        assertEquals(200, config.statusCode.value())

        // ClientInfo 헤더(x-app-version)와 함께 조회 — ios 3.5.0은 3.0~4.0 범위 안
        val adapted =
            client()
                .get()
                .uri("/v2/configs")
                .header("x-client-platform", "ios")
                .header("x-client-key", "test-ios-key")
                .header("x-app-version", "3.5.0")
                .retrieve()
                .toEntity(Any::class.java)
        assertEquals(200, adapted.statusCode.value())
        val configs = adapted.body as Map<*, *>
        assertTrue(configs.containsKey("notice"))

        // 범위 밖 버전은 설정이 없다
        val outOfRange =
            client()
                .get()
                .uri("/v2/configs")
                .header("x-client-platform", "ios")
                .header("x-client-key", "test-ios-key")
                .header("x-app-version", "5.0.0")
                .retrieve()
                .toEntity(Any::class.java)
        assertEquals(emptyMap<Any, Any>(), outOfRange.body)
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
        val preferences = (asMap(saved)["pushPreferences"] as List<*>)
        assertEquals(1, preferences.size)
        assertEquals("LECTURE_UPDATE", (preferences[0] as Map<*, *>)["type"])
        assertEquals(false, (preferences[0] as Map<*, *>)["isEnabled"])
    }

    @Test
    fun `정적 페이지가 제공된다`() {
        val member =
            client()
                .get()
                .uri("/member")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, member.statusCode.value())
        assertTrue(member.body!!.contains("<html"))
    }

    private fun getNickname(localId: String): String = userRepository.findByLocalIdAndActiveTrue(localId)!!.nickname
}
