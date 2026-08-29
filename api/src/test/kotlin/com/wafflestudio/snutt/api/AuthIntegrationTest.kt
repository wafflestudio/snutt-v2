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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

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

    private val jsonMapper = JsonMapper.builder().build()

    private fun body(response: ResponseEntity<String>): JsonNode = jsonMapper.readTree(response.body!!)

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
        bearer: String? = null,
    ): ResponseEntity<String> =
        client()
            .post()
            .uri(uri)
            .headers { if (bearer != null) it.setBearerAuth(bearer) }
            .body(body)
            .retrieve()
            .toEntity(String::class.java)

    private fun get(
        uri: String,
        bearer: String? = null,
    ): ResponseEntity<String> =
        client()
            .get()
            .uri(uri)
            .headers { if (bearer != null) it.setBearerAuth(bearer) }
            .retrieve()
            .toEntity(String::class.java)

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
                .toEntity(String::class.java)
        assertEquals(403, response.statusCode.value())
        assertEquals(0x2000L.toInt(), body(response)["errcode"].asInt())
    }

    @Test
    @Order(2)
    fun `로컬 회원가입 시 토큰 쌍이 발급된다`() {
        val response =
            post("/v2/auth/register", """{"localId":"testuser1","password":"password1","email":"test@snu.ac.kr"}""")
        assertEquals(200, response.statusCode.value())
        val node = body(response)
        accessToken = node["accessToken"].asString()
        refreshToken = node["refreshToken"].asString()
        userId = node["userId"].asString()
        assertTrue(userId.toLong() > 0)
        assertTrue(accessToken.isNotBlank())
    }

    @Test
    @Order(3)
    fun `발급된 액세스 토큰으로 내 정보를 조회한다`() {
        val response = get("/v2/users/me", bearer = accessToken)
        assertEquals(200, response.statusCode.value())
        val node = body(response)
        assertEquals(userId, node["id"].asString())
        assertEquals("test@snu.ac.kr", node["email"].asString())
        assertEquals(listOf("local"), node["authProviders"].values().map { it.asString() })
    }

    @Test
    @Order(4)
    fun `중복 localId 회원가입은 거부된다`() {
        val response = post("/v2/auth/register", """{"localId":"testuser1","password":"password1"}""")
        assertEquals(403, response.statusCode.value())
        assertEquals(0x3002L.toInt(), body(response)["errcode"].asInt())
    }

    @Test
    @Order(5)
    fun `refresh 회전 후 이전 refresh 토큰은 거부되고 현재 로그인은 유지된다`() {
        val oldRefreshToken = refreshToken
        val rotated = post("/v2/auth/refresh", """{"refreshToken":"$oldRefreshToken"}""")
        assertEquals(200, rotated.statusCode.value())
        val newRefreshToken = body(rotated)["refreshToken"].asString()
        assertNotEquals(oldRefreshToken, newRefreshToken)

        // 회전으로 교체된 옛 토큰은 어느 행과도 일치하지 않는다.
        val reuse = post("/v2/auth/refresh", """{"refreshToken":"$oldRefreshToken"}""")
        assertEquals(401, reuse.statusCode.value())

        // 재사용 탐지를 하지 않으므로 옛 토큰이 제시돼도 로그인 자체는 살아있다.
        val afterReuse = post("/v2/auth/refresh", """{"refreshToken":"$newRefreshToken"}""")
        assertEquals(200, afterReuse.statusCode.value())
        refreshToken = body(afterReuse)["refreshToken"].asString()
    }

    @Test
    @Order(6)
    fun `로그인이 다시 동작하고 me 조회가 성공한다`() {
        val login = post("/v2/auth/login", """{"localId":"testuser1","password":"password1"}""")
        assertEquals(200, login.statusCode.value())
        val loginBody = body(login)
        accessToken = loginBody["accessToken"].asString()
        refreshToken = loginBody["refreshToken"].asString()

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
    fun `로그아웃하면 refresh token 이 만료된다`() {
        val response = post("/v2/auth/logout", """{"refreshToken":"$refreshToken"}""")
        assertEquals(200, response.statusCode.value())

        val afterLogout = post("/v2/auth/refresh", """{"refreshToken":"$refreshToken"}""")
        assertEquals(401, afterLogout.statusCode.value())
    }

    @Test
    @Order(9)
    fun `로그아웃해도 access token 은 만료 전까지 인증에 쓸 수 있다`() {
        // access token 검증은 상태를 조회하지 않으므로 로그아웃이 즉시 무효화하지 못한다.
        // 무효화는 access token TTL 만큼 지연되며, 이는 stateless 인증을 택한 대가다.
        val me = get("/v2/users/me", bearer = accessToken)
        assertEquals(200, me.statusCode.value())
    }
}
