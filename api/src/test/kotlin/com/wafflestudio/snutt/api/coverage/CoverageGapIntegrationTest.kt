package com.wafflestudio.snutt.api.coverage

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.RecordingPushClient
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.friend.model.Friend
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import com.wafflestudio.snutt.core.domain.theme.repository.PublishedThemeRepository
import com.wafflestudio.snutt.core.domain.theme.repository.TimetableThemeRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
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
 * 기존 snutt/snutt-ev 대비 누락되어 있던 기능들의 계약 검증:
 * 기기 등록, 학기 상태, 친구 테마/기본 테마, 친구 코스북 별칭, 최근 수강 강의.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoverageGapIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("coverage_gap_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired lateinit var coursebookRepository: CoursebookRepository

    @Autowired lateinit var lectureRepository: LectureRepository

    @Autowired lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired lateinit var courseRepository: CourseRepository

    @Autowired lateinit var themeRepository: TimetableThemeRepository

    @Autowired lateinit var publishedThemeRepository: PublishedThemeRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var recordingPushClient: RecordingPushClient

    @LocalServerPort var port = 0

    private lateinit var userAToken: String
    private lateinit var userBToken: String
    private lateinit var lectureId: String

    @BeforeAll
    fun seed() {
        // 현재 학기 + 직전 두 학기 (최근 수강 강의는 직전 두 학기를 본다)
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.SPRING))
        coursebookRepository.save(Coursebook(year = 2025, semester = Semester.AUTUMN))

        val course =
            courseRepository.save(
                Course(
                    courseNumber = "M2174.001600",
                    instructor = "박지수",
                    title = "생활과학신입생세미나",
                    department = "생활과학대학",
                    classification = "전필",
                ),
            )
        lectureId =
            saveLectureWithTimes(
                lectureRepository,
                lectureClassTimeRepository,
                Lecture(
                    year = 2026,
                    semester = Semester.SPRING,
                    courseNumber = "M2174.001600",
                    lectureNumber = "001",
                    courseTitle = "생활과학신입생세미나",
                    instructor = "박지수",
                    department = "생활과학대학",
                    academicYear = "1학년",
                    classification = "전필",
                    credit = 1,
                    quota = 150,
                ).also { it.courseId = course.id },
                listOf(ClassPlaceAndTime(DayOfWeek.TUESDAY, "222-701", 1020, 1070)),
            ).externalId

        userAToken = register("coverusera", "coverusera@snu.ac.kr")
        userBToken = register("coveruserb", "coveruserb@snu.ac.kr")
    }

    private fun register(
        localId: String,
        email: String,
    ): String {
        val response = post("/v2/auth/register", """{"localId":"$localId","password":"password1","email":"$email"}""")
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

    private fun post(
        uri: String,
        body: String? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ResponseEntity<Any> {
        val spec = client().post().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        headers.forEach { (k, v) -> spec.header(k, v) }
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(Any::class.java)
    }

    private fun get(
        uri: String,
        token: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().get().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(Any::class.java)
    }

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
    fun `기기 등록과 해제가 FCM 토픽 구독까지 반영한다`() {
        val registrationId = "fcm-token-abc"
        val register =
            post(
                "/v2/users/me/devices/$registrationId",
                token = userAToken,
                headers = mapOf("x-device-id" to "device-1", "x-os-type" to "ios", "x-app-type" to "release"),
            )
        assertEquals(200, register.statusCode.value())
        assertTrue(recordingPushClient.globalTopicSubscriptions.contains(registrationId))

        // 같은 기기에서 토큰이 갱신되면 행이 늘지 않고 갱신된다
        post(
            "/v2/users/me/devices/fcm-token-def",
            token = userAToken,
            headers = mapOf("x-device-id" to "device-1", "x-os-type" to "ios"),
        )
        val userId = userRepository.findByLocalIdAndActiveTrue("coverusera")!!.id!!
        assertEquals(1, deviceCount(userId, "device-1"))

        val removed = delete("/v2/users/me/devices/fcm-token-def", userAToken)
        assertEquals(200, removed.statusCode.value())
        assertEquals(0, deviceCount(userId, "device-1"))
        assertTrue(!recordingPushClient.globalTopicSubscriptions.contains("fcm-token-def"))
    }

    private fun deviceCount(
        userId: Long,
        deviceId: String,
    ): Int = userDeviceRepository.findAllByUserIdAndIsDeletedFalse(userId).count { it.deviceId == deviceId }

    @Autowired
    lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired
    lateinit var legacyTokenService: com.wafflestudio.snutt.v1compat.auth.LegacyTokenService

    @Test
    fun `구 경로로도 기기를 등록한다`() {
        val legacyToken = legacyTokenService.issue(userRepository.findByLocalIdAndActiveTrue("coverusera")!!)
        val response =
            client()
                .post()
                .uri("/v1/user/device/legacy-fcm-token")
                .header("x-access-token", legacyToken)
                .header("x-device-id", "legacy-device")
                .retrieve()
                .toEntity(Any::class.java)
        assertEquals(200, response.statusCode.value())
        assertTrue(recordingPushClient.globalTopicSubscriptions.contains("legacy-fcm-token"))
    }

    @Test
    fun `학기 상태는 현재와 다음 학기를 알려준다`() {
        val response = get("/v2/semesters/status")
        assertEquals(200, response.statusCode.value())
        val body = asMap(response)
        // 방학 중이면 current가 없을 수 있지만 next는 항상 존재한다
        assertNotNull(body["next"])
        val next = body["next"] as Map<String, Any?>
        assertNotNull(next["year"])
        assertNotNull(next["semester"])
    }

    @Test
    fun `친구가 공유한 테마를 조회한다`() {
        val userB = userRepository.findByLocalIdAndActiveTrue("coveruserb")!!
        acceptedFriend()
        val published =
            themeRepository.save(
                TimetableTheme(
                    userId = userB.id!!,
                    name = "친구테마",
                    colorList = listOf(ColorSet(backgroundColor = "#111111", foregroundColor = "#222222")),
                ),
            )
        publishedThemeRepository.save(
            PublishedTheme(
                themeId = published.id!!,
                publishName = "친구가공유한테마",
                downloadCount = 7,
            ),
        )

        val response = get("/v2/themes/friends?page=0", userAToken)
        assertEquals(200, response.statusCode.value())
        val themes = asList(response)
        assertEquals(1, themes.size)
        assertEquals("친구가공유한테마", themes[0]["publishName"])
    }

    @Autowired
    lateinit var friendRepository: FriendRepository

    // 친구 쌍은 유니크 제약이 있어 테스트 간 재사용한다
    @Synchronized
    private fun acceptedFriend(): Friend {
        val userA = userRepository.findByLocalIdAndActiveTrue("coverusera")!!
        val userB = userRepository.findByLocalIdAndActiveTrue("coveruserb")!!
        return friendRepository.findByUserPair(userA.id!!, userB.id!!)
            ?: friendRepository.save(
                Friend(
                    fromUserId = userA.id!!,
                    toUserId = userB.id!!,
                    isAccepted = true,
                ),
            )
    }

    @Test
    fun `커스텀 테마를 기본 테마로 지정하고 해제한다`() {
        val created =
            post(
                "/v2/themes",
                """{"name":"기본테마후보","colorList":[{"backgroundColor":"#000000","foregroundColor":"#ffffff"}]}""",
                userBToken,
            )
        assertEquals(200, created.statusCode.value())
        val themeId = asMap(created)["id"] as String

        val setDefault = post("/v2/themes/$themeId/default", token = userBToken)
        assertEquals(200, setDefault.statusCode.value())
        assertEquals(true, asMap(setDefault)["isDefault"])

        // 목록에서도 기본 테마로 보인다
        val themes = asList(get("/v2/themes", userBToken))
        val marked = themes.filter { it["isDefault"] == true }
        assertEquals(1, marked.size)
        assertEquals(themeId, marked[0]["id"])

        val unset = delete("/v2/themes/$themeId/default", userBToken)
        assertEquals(200, unset.statusCode.value())
        assertEquals("SNUTT", asMap(unset)["name"])
    }

    @Test
    fun `친구 코스북은 구 경로 별칭으로도 조회된다`() {
        val friend = acceptedFriend()
        val aliased = get("/v2/friends/${friend.externalId}/registered-course-books", userAToken)
        assertEquals(200, aliased.statusCode.value())
        val canonical = get("/v2/friends/${friend.externalId}/coursebooks", userAToken)
        assertEquals(canonical.body, aliased.body)
    }

    @Test
    fun `최근 수강 강의를 강의평 작성 대상으로 돌려준다`() {
        // 직전 학기(2026 봄) 시간표에 강의를 담는다
        val table = post("/v2/timetables", """{"year":2026,"semester":1,"title":"수강내역"}""", userAToken)
        assertEquals(200, table.statusCode.value())
        val tableId = asList(table).first { it["title"] == "수강내역" }["id"] as String
        val added = post("/v2/timetables/$tableId/lectures", """{"lectureId":"$lectureId"}""", userAToken)
        assertEquals(200, added.statusCode.value())

        val response = get("/v2/users/me/lectures/latest", userAToken)
        assertEquals(200, response.statusCode.value())
        val lectures = asList(response)
        assertEquals(1, lectures.size)
        assertEquals("생활과학신입생세미나", lectures[0]["title"])
        assertEquals(2026, lectures[0]["takenYear"])
    }
}
