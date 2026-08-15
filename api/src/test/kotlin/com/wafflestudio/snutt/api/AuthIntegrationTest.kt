package com.wafflestudio.snutt.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AuthIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("auth_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }

        var accessToken = ""
        var refreshToken = ""
        var userId = ""
    }

    @LocalServerPort
    var port = 0

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
        bearer: String? = null,
    ): ResponseEntity<Map<*, *>> =
        client()
            .post()
            .uri(uri)
            .headers { if (bearer != null) it.setBearerAuth(bearer) }
            .body(body)
            .retrieve()
            .toEntity(Map::class.java)

    private fun get(
        uri: String,
        bearer: String? = null,
    ): ResponseEntity<Map<*, *>> =
        client()
            .get()
            .uri(uri)
            .headers { if (bearer != null) it.setBearerAuth(bearer) }
            .retrieve()
            .toEntity(Map::class.java)

    @Test
    @Order(1)
    fun `플랫폼 키 없이 v2 요청은 거부된다`() {
        val response =
            RestClient
                .builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }) { _, _ -> }
                .build()
                .get()
                .uri("/v2/users/me")
                .retrieve()
                .toEntity(Map::class.java)
        assertEquals(403, response.statusCode.value())
        assertEquals(0x2000L.toInt(), response.body!!["errcode"])
    }

    @Test
    @Order(2)
    fun `로컬 회원가입 시 토큰 쌍이 발급된다`() {
        val response =
            post("/v2/auth/register", """{"localId":"testuser1","password":"password1","email":"test@snu.ac.kr"}""")
        assertEquals(200, response.statusCode.value())
        accessToken = response.body!!["accessToken"] as String
        refreshToken = response.body!!["refreshToken"] as String
        userId = response.body!!["userId"] as String
        assertTrue(userId.matches(Regex("^[0-9a-f]{24}$")))
        assertTrue(accessToken.isNotBlank())
    }

    @Test
    @Order(3)
    fun `발급된 액세스 토큰으로 내 정보를 조회한다`() {
        val response = get("/v2/users/me", bearer = accessToken)
        assertEquals(200, response.statusCode.value())
        assertEquals(userId, response.body!!["id"])
        assertEquals("test@snu.ac.kr", response.body!!["email"])
        assertEquals(listOf("local"), response.body!!["authProviders"])
    }

    @Test
    @Order(4)
    fun `중복 localId 회원가입은 거부된다`() {
        val response = post("/v2/auth/register", """{"localId":"testuser1","password":"password1"}""")
        assertEquals(403, response.statusCode.value())
        assertEquals(0x3002L.toInt(), response.body!!["errcode"])
    }

    @Test
    @Order(5)
    fun `refresh 회전 후 이전 refresh 토큰 재사용은 전체 세션을 폐기한다`() {
        val oldRefreshToken = refreshToken
        val rotated = post("/v2/auth/refresh", """{"refreshToken":"$oldRefreshToken"}""")
        assertEquals(200, rotated.statusCode.value())
        val newRefreshToken = rotated.body!!["refreshToken"] as String
        assertNotEquals(oldRefreshToken, newRefreshToken)

        val reuse = post("/v2/auth/refresh", """{"refreshToken":"$oldRefreshToken"}""")
        assertEquals(401, reuse.statusCode.value())

        val afterReuse = post("/v2/auth/refresh", """{"refreshToken":"$newRefreshToken"}""")
        assertEquals(401, afterReuse.statusCode.value())
    }

    @Test
    @Order(6)
    fun `로그인이 다시 동작하고 me 조회가 성공한다`() {
        val login = post("/v2/auth/login", """{"localId":"testuser1","password":"password1"}""")
        assertEquals(200, login.statusCode.value())
        accessToken = login.body!!["accessToken"] as String

        val me = get("/v2/users/me", bearer = accessToken)
        assertEquals(200, me.statusCode.value())
    }

    @Test
    @Order(7)
    fun `잘못된 형식의 토큰은 거부된다`() {
        val response = get("/v2/users/me", bearer = "invalid.token.value")
        assertEquals(403, response.statusCode.value())
    }

    @Test
    @Order(8)
    fun `로그아웃하면 세션이 폐기된다`() {
        val response = post("/v2/auth/logout", """{}""", bearer = accessToken)
        assertEquals(200, response.statusCode.value())
    }
}
