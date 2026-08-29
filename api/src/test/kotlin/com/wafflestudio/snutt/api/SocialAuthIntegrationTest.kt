package com.wafflestudio.snutt.api

import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SocialAuthIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("social_auth_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @MockitoBean(name = "GOOGLE")
    private lateinit var googleClient: OAuth2Client

    @MockitoBean(name = "APPLE")
    private lateinit var appleClient: OAuth2Client

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
        payload: String,
        bearer: String? = null,
    ): ResponseEntity<String> =
        client()
            .post()
            .uri(uri)
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .body(payload)
            .retrieve()
            .toEntity(String::class.java)

    private fun get(
        uri: String,
        bearer: String,
    ): ResponseEntity<String> =
        client()
            .get()
            .uri(uri)
            .header("Authorization", "Bearer $bearer")
            .retrieve()
            .toEntity(String::class.java)

    private fun delete(
        uri: String,
        bearer: String,
    ): ResponseEntity<String> =
        client()
            .delete()
            .uri(uri)
            .header("Authorization", "Bearer $bearer")
            .retrieve()
            .toEntity(String::class.java)

    private fun providers(response: ResponseEntity<String>): List<String> =
        body(response)["authProviders"].values().map { it.asString().uppercase() }

    @Test
    fun `구글 소셜 로그인으로 가입하고 재로그인해도 같은 계정이다`() {
        Mockito
            .`when`(googleClient.getMe("google-token"))
            .thenReturn(OAuth2UserResponse(socialId = "g-sub-1", name = null, email = "gsocial@snu.ac.kr", isEmailVerified = true))

        val first = post("/v2/auth/login/google", """{"token":"google-token"}""")
        assertEquals(200, first.statusCode.value(), "body=${first.body}")
        val accessToken = body(first)["accessToken"].asString()
        val userId = body(first)["userId"].asLong()

        val second = post("/v2/auth/login/google", """{"token":"google-token"}""")
        assertEquals(userId, body(second)["userId"].asLong())

        val me = body(get("/v2/users/me", accessToken))
        assertEquals(listOf("GOOGLE"), me["authProviders"].values().map { it.asString().uppercase() })
    }

    @Test
    fun `로컬 계정에 소셜을 연결했다가 해제한다`() {
        val register =
            post(
                "/v2/auth/register",
                """{"localId":"socialattach","password":"password1","email":"socialattach@snu.ac.kr"}""",
            )
        val accessToken = body(register)["accessToken"].asString()

        Mockito
            .`when`(googleClient.getMe("google-token"))
            .thenReturn(OAuth2UserResponse(socialId = "g-sub-2", name = null, email = null, isEmailVerified = false))

        val attach = post("/v2/users/me/social/google", """{"token":"google-token"}""", accessToken)
        assertEquals(200, attach.statusCode.value(), "body=${attach.body}")
        assertEquals(listOf("LOCAL", "GOOGLE"), providers(attach))

        val detach = delete("/v2/users/me/social/google", accessToken)
        assertEquals(200, detach.statusCode.value())
        assertEquals(listOf("LOCAL"), providers(detach))
    }

    @Test
    fun `마지막 로그인 수단은 해제할 수 없다`() {
        Mockito
            .`when`(googleClient.getMe("google-token"))
            .thenReturn(OAuth2UserResponse(socialId = "g-sub-3", name = null, email = "glast@snu.ac.kr", isEmailVerified = true))
        val login = post("/v2/auth/login/google", """{"token":"google-token"}""")
        val accessToken = body(login)["accessToken"].asString()

        val detach = delete("/v2/users/me/social/google", accessToken)
        assertEquals(409, detach.statusCode.value())
    }

    @Test
    fun `애플 transfer sub가 바뀌면 같은 계정으로 재연결된다`() {
        Mockito
            .`when`(appleClient.getMe("apple-token-1"))
            .thenReturn(
                OAuth2UserResponse(
                    socialId = "apple-sub-1",
                    name = null,
                    email = "applesocial@snu.ac.kr",
                    isEmailVerified = true,
                    transferInfo = "transfer-1",
                ),
            )
        val first = post("/v2/auth/login/apple", """{"token":"apple-token-1"}""")
        assertEquals(200, first.statusCode.value(), "body=${first.body}")
        val userId = body(first)["userId"].asLong()

        // 애플이 sub를 갱신하면 transfer sub로 기존 계정을 찾아 연결 정보를 갱신한다
        Mockito
            .`when`(appleClient.getMe("apple-token-2"))
            .thenReturn(
                OAuth2UserResponse(
                    socialId = "apple-sub-2",
                    name = null,
                    email = "applesocial@snu.ac.kr",
                    isEmailVerified = true,
                    transferInfo = "transfer-1",
                ),
            )
        val relogin = post("/v2/auth/login/apple", """{"token":"apple-token-2"}""")
        assertEquals(userId, body(relogin)["userId"].asLong())

        // 갱신된 sub로 직접 로그인 가능해야 한다
        val again = post("/v2/auth/login/apple", """{"token":"apple-token-2"}""")
        assertEquals(userId, body(again)["userId"].asLong())
    }
}
