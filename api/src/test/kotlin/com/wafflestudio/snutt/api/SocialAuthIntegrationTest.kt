package com.wafflestudio.snutt.api

import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.ResponseEntity
import org.springframework.test.context.bean.override.mockito.MockitoBean

class SocialAuthIntegrationTest : AbstractApiIntegrationTest() {
    @MockitoBean(name = "GOOGLE")
    private lateinit var googleClient: OAuth2Client

    @MockitoBean(name = "APPLE")
    private lateinit var appleClient: OAuth2Client

    private fun providers(response: ResponseEntity<String>): List<String> =
        body(response)["authProviders"].values().map { it.asString().uppercase() }

    @Test
    fun `구글 소셜 로그인으로 가입하고 재로그인해도 같은 계정이다`() {
        Mockito
            .`when`(googleClient.getMe("google-token"))
            .thenReturn(OAuth2UserResponse(socialId = "g-sub-1", name = null, email = "gsocial@snu.ac.kr"))

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
            .thenReturn(OAuth2UserResponse(socialId = "g-sub-2", name = null, email = null))

        val attach = post("/v2/users/me/social/google", """{"token":"google-token"}""", accessToken)
        assertEquals(200, attach.statusCode.value(), "body=${attach.body}")
        assertEquals(listOf("LOCAL", "GOOGLE"), providers(attach))

        val detach = delete("/v2/users/me/social/google", body(attach)["accessToken"].asString())
        assertEquals(200, detach.statusCode.value())
        assertEquals(listOf("LOCAL"), providers(detach))
    }

    @Test
    fun `마지막 로그인 수단은 해제할 수 없다`() {
        Mockito
            .`when`(googleClient.getMe("google-token"))
            .thenReturn(OAuth2UserResponse(socialId = "g-sub-3", name = null, email = "glast@snu.ac.kr"))
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
                    transferInfo = "transfer-1",
                ),
            )
        val first = post("/v2/auth/login/apple", """{"token":"apple-token-1"}""")
        assertEquals(200, first.statusCode.value(), "body=${first.body}")
        val userId = body(first)["userId"].asLong()

        Mockito
            .`when`(appleClient.getMe("apple-token-2"))
            .thenReturn(
                OAuth2UserResponse(
                    socialId = "apple-sub-2",
                    name = null,
                    email = "applesocial@snu.ac.kr",
                    transferInfo = "transfer-1",
                ),
            )
        val relogin = post("/v2/auth/login/apple", """{"token":"apple-token-2"}""")
        assertEquals(userId, body(relogin)["userId"].asLong())

        val again = post("/v2/auth/login/apple", """{"token":"apple-token-2"}""")
        assertEquals(userId, body(again)["userId"].asLong())
    }
}
