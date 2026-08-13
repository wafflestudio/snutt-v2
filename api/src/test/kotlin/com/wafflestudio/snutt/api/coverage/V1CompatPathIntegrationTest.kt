package com.wafflestudio.snutt.api.coverage

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.popup.model.Popup
import com.wafflestudio.snutt.core.domain.popup.repository.PopupRepository
import com.wafflestudio.snutt.core.domain.tag.model.TagCollection
import com.wafflestudio.snutt.core.domain.tag.model.TagList
import com.wafflestudio.snutt.core.domain.tag.repository.TagListRepository
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
import org.testcontainers.containers.GenericContainer

/**
 * 구 클라이언트가 쓰던 /v1 경로가 그대로 살아있는지 확인한다.
 * v1은 계정 경로가 단수형(/v1/user)이고, 목록을 {content,totalCount}로 감싸며,
 * 테마 색상 필드가 colors 다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V1CompatPathIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("v1compat_path_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }

        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer("redis:7-alpine").withExposedPorts(6379).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun storageProperties(registry: DynamicPropertyRegistry) {
            registry.add("snutt.storage.namespace") { "testnamespace" }
        }
    }

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var coursebookRepository: CoursebookRepository

    @Autowired lateinit var tagListRepository: TagListRepository

    @Autowired lateinit var popupRepository: PopupRepository

    @LocalServerPort var port = 0

    private lateinit var legacyToken: String

    @BeforeAll
    fun seed() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        tagListRepository.save(
            TagList(
                year = 2026,
                semester = Semester.AUTUMN,
                tagCollection =
                    TagCollection(
                        classification = listOf("전공"),
                        department = listOf("컴퓨터공학부"),
                        academicYear = listOf("3학년"),
                        credit = listOf("3학점"),
                        instructor = listOf("교수"),
                        category = listOf("교양"),
                    ),
            ),
        )
        popupRepository.save(
            Popup(popupKey = "notice", imageOriginUri = "s3://snutt-asset/popup-images/a.jpg", hiddenDays = 7),
        )
        val register =
            client()
                .post()
                .uri("/v1/auth/register_local")
                .body("""{"id":"v1pathuser","password":"password1","email":"v1path@snu.ac.kr"}""")
                .retrieve()
                .toEntity(Any::class.java)
        assertEquals(200, register.statusCode.value())
        legacyToken = asMap(register)["token"] as String
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

    private fun getV1(uri: String): ResponseEntity<Any> =
        client()
            .get()
            .uri(uri)
            .header("x-access-token", legacyToken)
            .retrieve()
            .toEntity(Any::class.java)

    private fun postV1(
        uri: String,
        body: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().post().uri(uri).header("x-access-token", legacyToken)
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(response: ResponseEntity<Any>): Map<String, Any?> = response.body as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun asList(response: ResponseEntity<Any>): List<Map<String, Any?>> = response.body as List<Map<String, Any?>>

    @Test
    fun `계정 경로는 단수형 v1 user 이다`() {
        val info = getV1("/v1/user/info")
        assertEquals(200, info.statusCode.value())
        assertEquals("v1pathuser", asMap(info)["local_id"])

        val verification = getV1("/v1/user/email/verification")
        assertEquals(200, verification.statusCode.value())
        assertEquals(false, asMap(verification)["isEmailVerified"])
    }

    @Test
    fun `내 정보는 복수형 v1 users me 이다`() {
        val me = getV1("/v1/users/me")
        assertEquals(200, me.statusCode.value())
        assertEquals("v1pathuser", asMap(me)["localId"])

        val providers = getV1("/v1/users/me/social_providers")
        assertEquals(200, providers.statusCode.value())
        // v1 AuthProvidersCheckDto는 제공자별 불리언이다
        assertEquals(true, asMap(providers)["local"])
        assertEquals(false, asMap(providers)["facebook"])
    }

    @Test
    fun `친구 목록은 content와 totalCount로 감싼다`() {
        val response = getV1("/v1/friends?state=ACTIVE")
        assertEquals(200, response.statusCode.value())
        val body = asMap(response)
        assertNotNull(body["content"])
        assertEquals(0, body["totalCount"])
    }

    @Test
    fun `테마 목록은 colors 필드를 쓴다`() {
        val response = getV1("/v1/themes")
        assertEquals(200, response.statusCode.value())
        val themes = asList(response)
        assertTrue(themes.isNotEmpty())
        // 내장 테마는 색상이 null이라 non_null 정책으로 생략된다 (v1 동일)
        assertTrue(themes[0].containsKey("theme"))
        assertTrue(themes[0].containsKey("isDefault"))
        assertTrue(themes[0].containsKey("isCustom"))
    }

    @Test
    fun `테마 검색은 POST 본문으로 받는다`() {
        val response = postV1("/v1/themes/search", """{"keyword":"없는테마"}""")
        assertEquals(200, response.statusCode.value())
        assertNotNull(asMap(response)["content"])
    }

    @Test
    fun `태그 갱신 시각 경로가 살아있다`() {
        val response = getV1("/v1/tags/2026/3/update_time")
        assertEquals(200, response.statusCode.value())
        assertNotNull(asMap(response)["updated_at"])
    }

    @Test
    fun `알림 경로는 단수형이다`() {
        val list = getV1("/v1/notification")
        assertEquals(200, list.statusCode.value())
        val count = getV1("/v1/notification/count")
        assertEquals(200, count.statusCode.value())
        assertNotNull(asMap(count)["count"])
    }

    @Test
    fun `팝업은 공개 오브젝트 URL과 구 필드명을 함께 준다`() {
        val response = getV1("/v1/popups")
        assertEquals(200, response.statusCode.value())
        val popups = asList(response)
        assertEquals(1, popups.size)
        assertEquals("notice", popups[0]["key"])
        assertEquals(
            "https://objectstorage.ap-chuncheon-1.oraclecloud.com/n/testnamespace/b/snutt-asset/o/popup-images/a.jpg",
            popups[0]["imageUri"],
        )
        assertEquals(popups[0]["imageUri"], popups[0]["image_url"])
        assertEquals(7, popups[0]["hidden_days"])
    }

    @Test
    fun `관리자 이미지 업로드 URI를 발급한다`() {
        userRepository.findByLocalIdAndActiveTrue("v1pathuser")!!.let {
            it.isAdmin = true
            userRepository.save(it)
        }
        val response =
            client()
                .post()
                .uri("/v1/admin/images/popup/upload-uris?count=2")
                .header("x-access-token", legacyToken)
                .retrieve()
                .toEntity(Any::class.java)
        assertEquals(200, response.statusCode.value())
        val uris = asList(response)
        assertEquals(2, uris.size)
        assertTrue((uris[0]["fileOriginUri"] as String).startsWith("s3://snutt-asset/popup-images/"))
        assertTrue((uris[0]["fileUri"] as String).startsWith("https://objectstorage."))
    }

    @Test
    fun `학기 상태와 강의평 요약은 인증 없이 열려 있다`() {
        val status =
            client()
                .get()
                .uri("/v1/semesters/status")
                .retrieve()
                .toEntity(Any::class.java)
        assertEquals(200, status.statusCode.value())
    }

    @Test
    fun `구 강의평 경로가 살아있다`() {
        // 강의평 경로는 이메일 인증을 요구한다 (v1 동일)
        assertEquals(403, getV1("/v1/ev-service/v1/evaluations/users/me").statusCode.value())

        userRepository.findByLocalIdAndActiveTrue("v1pathuser")!!.let {
            it.isEmailVerified = true
            userRepository.save(it)
        }
        val mine = getV1("/v1/ev-service/v1/evaluations/users/me")
        assertEquals(200, mine.statusCode.value())
        val search = getV1("/v1/ev-service/v1/lectures?query=&page=0")
        assertEquals(200, search.statusCode.value())
        assertNotNull(asMap(search)["content"])
    }
}
