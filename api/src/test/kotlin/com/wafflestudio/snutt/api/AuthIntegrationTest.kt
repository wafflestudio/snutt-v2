package com.wafflestudio.snutt.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

class AuthIntegrationTest : AbstractMysqlIntegrationTest() {
    private fun registerResponse(localId: String): JsonNode {
        val response = post("/v2/auth/register", """{"localId":"$localId","password":"password1","email":"$localId@snu.ac.kr"}""")
        assertEquals(200, response.statusCode.value())
        return body(response)
    }

    @Test
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
    }

    @Test
    fun `로컬 회원가입 시 토큰 쌍이 발급된다`() {
        val node = registerResponse("testuser1")
        assertTrue(node["userId"].asString().toLong() > 0)
        assertTrue(node["accessToken"].asString().isNotBlank())
        assertTrue(node["refreshToken"].asString().isNotBlank())
    }

    @Test
    fun `발급된 액세스 토큰으로 내 정보를 조회한다`() {
        val registered = registerResponse("testuser1")
        val response = get("/v2/users/me", token = registered["accessToken"].asString())
        assertEquals(200, response.statusCode.value())
        val node = body(response)
        assertEquals(registered["userId"].asString(), node["id"].asString())
        assertEquals("testuser1@snu.ac.kr", node["email"].asString())
        assertEquals(listOf("local"), node["authProviders"].values().map { it.asString() })
    }

    @Test
    fun `중복 localId 회원가입은 거부된다`() {
        registerResponse("testuser1")
        val response = post("/v2/auth/register", """{"localId":"testuser1","password":"password1"}""")
        assertEquals(409, response.statusCode.value())
    }

    @Test
    fun `refresh 회전 후 이전 refresh 토큰은 거부되고 현재 로그인은 유지된다`() {
        val oldRefreshToken = registerResponse("testuser1")["refreshToken"].asString()
        val rotated = post("/v2/auth/refresh", """{"refreshToken":"$oldRefreshToken"}""")
        assertEquals(200, rotated.statusCode.value())
        val newRefreshToken = body(rotated)["refreshToken"].asString()
        assertNotEquals(oldRefreshToken, newRefreshToken)

        val reuse = post("/v2/auth/refresh", """{"refreshToken":"$oldRefreshToken"}""")
        assertEquals(401, reuse.statusCode.value())

        val afterReuse = post("/v2/auth/refresh", """{"refreshToken":"$newRefreshToken"}""")
        assertEquals(200, afterReuse.statusCode.value())
    }

    @Test
    fun `로그인이 동작하고 me 조회가 성공한다`() {
        registerResponse("testuser1")
        val login = post("/v2/auth/login", """{"localId":"testuser1","password":"password1"}""")
        assertEquals(200, login.statusCode.value())

        val me = get("/v2/users/me", token = body(login)["accessToken"].asString())
        assertEquals(200, me.statusCode.value())
    }

    @Test
    fun `잘못된 형식의 토큰은 거부된다`() {
        val response = get("/v2/users/me", token = "invalid.token.value")
        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun `로그아웃하면 refresh token 이 만료된다`() {
        val refreshToken = registerResponse("testuser1")["refreshToken"].asString()
        val response = post("/v2/auth/logout", """{"refreshToken":"$refreshToken"}""")
        assertEquals(200, response.statusCode.value())

        val afterLogout = post("/v2/auth/refresh", """{"refreshToken":"$refreshToken"}""")
        assertEquals(401, afterLogout.statusCode.value())
    }

    @Test
    fun `로그아웃해도 access token 은 만료 전까지 인증에 쓸 수 있다`() {
        val registered = registerResponse("testuser1")
        val accessToken = registered["accessToken"].asString()
        post("/v2/auth/logout", """{"refreshToken":"${registered["refreshToken"].asString()}"}""")

        val me = get("/v2/users/me", token = accessToken)
        assertEquals(200, me.statusCode.value())
    }
}
