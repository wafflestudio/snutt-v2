package com.wafflestudio.snutt.api

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.wafflestudio.snutt.api.testutil.OidcTestIssuer
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.APPLE_JWKS
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.GOOGLE_USER_INFO
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.fixture
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.json
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.slow
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.status
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import java.time.Duration

/**
 * 소셜 로그인. 제공자 API는 WireMock으로, Apple·Facebook OIDC 토큰은 [OidcTestIssuer]의 테스트 키로 서명해 실제 검증 경로를 탄다.
 * 오류 규칙(`fetchSocialProfile`, `OidcJwtVerifier`): 제공자가 4xx면 401 social-login-failed, 5xx·타임아웃·응답 형식 이상이면
 * 502 social-provider-unavailable, 토큰 서명·iss·aud·만료가 틀리면 401.
 */
class SocialAuthIntegrationTest : AbstractApiIntegrationTest() {
    @Autowired
    lateinit var userRepository: UserRepository

    private val social get() = SocialProviderStubs(wireMock)

    private fun providers(response: ResponseEntity<String>): List<String> = body(response)["authProviders"].values().map { it.asString() }

    private fun login(
        provider: String,
        token: String,
    ) = post("/v2/auth/login/$provider", """{"token":"$token"}""")

    private fun assertProblem(
        response: ResponseEntity<String>,
        status: Int,
        type: String,
    ) {
        assertEquals(status, response.statusCode.value(), "body=${response.body}")
        assertEquals("/problems/$type", body(response)["type"].asString())
    }

    // --- Google ---

    @Test
    fun `구글 소셜 로그인으로 가입하고 재로그인해도 같은 계정이다`() {
        social.googleUserInfo("google-token", json(fixture("google/userinfo.json")))

        val first = login("GOOGLE", "google-token")
        assertEquals(200, first.statusCode.value(), "body=${first.body}")
        val accessToken = body(first)["accessToken"].asString()
        val userId = body(first)["userId"].asLong()

        val second = login("GOOGLE", "google-token")
        assertEquals(userId, body(second)["userId"].asLong())

        val me = body(get("/v2/users/me", accessToken))
        assertEquals(listOf("GOOGLE"), me["authProviders"].values().map { it.asString() })
        assertEquals("gsocial@snu.ac.kr", userRepository.findById(userId).get().email)
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(GOOGLE_USER_INFO)).withHeader("Authorization", equalTo("Bearer google-token")))
    }

    @Test
    fun `로컬 계정에 소셜을 연결했다가 해제한다`() {
        val accessToken = register("socialattach")
        social.googleUserInfo("google-token", json("""{"id":"g-sub-2","email":"other@gmail.com"}"""))

        val attach = post("/v2/users/me/social/GOOGLE", """{"token":"google-token"}""", accessToken)
        assertEquals(200, attach.statusCode.value(), "body=${attach.body}")
        assertEquals(listOf("LOCAL", "GOOGLE"), providers(attach))

        val detach = delete("/v2/users/me/social/GOOGLE", body(attach)["accessToken"].asString())
        assertEquals(200, detach.statusCode.value())
        assertEquals(listOf("LOCAL"), providers(detach))
    }

    @Test
    fun `마지막 로그인 수단은 해제할 수 없다`() {
        social.googleUserInfo("google-token", json(fixture("google/userinfo.json")))
        val accessToken = body(login("GOOGLE", "google-token"))["accessToken"].asString()

        val detach = delete("/v2/users/me/social/GOOGLE", accessToken)
        assertEquals(409, detach.statusCode.value())
    }

    @Test
    fun `구글 토큰이 만료돼 401이면 로그인 실패이고 계정을 만들지 않는다`() {
        social.googleUserInfo("expired-token", json(fixture("google/error-401.json"), status = 401))

        assertProblem(login("GOOGLE", "expired-token"), 401, "social-login-failed")
        assertEquals(0, userRepository.count())
    }

    @Test
    fun `구글이 5xx면 502로 제공자 장애를 알린다`() {
        social.googleUserInfo("google-token", status(500))

        assertProblem(login("GOOGLE", "google-token"), 502, "social-provider-unavailable")
        assertEquals(0, userRepository.count())
    }

    @Test
    fun `구글 응답이 타임아웃되면 502다`() {
        social.googleUserInfo("google-token", slow(json(fixture("google/userinfo.json"))))

        assertProblem(login("GOOGLE", "google-token"), 502, "social-provider-unavailable")
    }

    @Test
    fun `구글 응답에 id가 없거나 바디가 잘리면 502이고 계정을 만들지 않는다`() {
        social.googleUserInfo("no-id", json("""{"email":"gsocial@snu.ac.kr","verified_email":true}"""))
        val body = fixture("google/userinfo.json")
        social.googleUserInfo("truncated", json(body.substring(0, body.length / 2)))

        assertProblem(login("GOOGLE", "no-id"), 502, "social-provider-unavailable")
        assertProblem(login("GOOGLE", "truncated"), 502, "social-provider-unavailable")
        assertEquals(0, userRepository.count())
    }

    // --- Kakao ---

    @Test
    fun `카카오 소셜 로그인으로 가입한다`() {
        social.kakaoUserMe("kakao-token", json(fixture("kakao/user-me.json")))

        val response = login("KAKAO", "kakao-token")

        assertEquals(200, response.statusCode.value(), "body=${response.body}")
        val user = userRepository.findById(body(response)["userId"].asLong()).get()
        assertEquals("ksocial@snu.ac.kr", user.email)
    }

    @Test
    fun `카카오 토큰이 없거나 만료돼 401이면 로그인 실패다`() {
        social.kakaoUserMe("expired-token", json(fixture("kakao/error-401.json"), status = 401))

        assertProblem(login("KAKAO", "expired-token"), 401, "social-login-failed")
    }

    @Test
    fun `카카오 계정에 이메일 동의가 없으면 응답 형식 이상으로 보고 502다`() {
        // KakaoAccountDto.email이 non-null이라 역직렬화가 실패한다(v1도 email을 필수로 받았다).
        social.kakaoUserMe("kakao-token", json(fixture("kakao/user-me-no-email.json")))

        assertProblem(login("KAKAO", "kakao-token"), 502, "social-provider-unavailable")
        assertEquals(0, userRepository.count())
    }

    // --- Facebook ---

    @Test
    fun `페이스북 액세스 토큰으로 가입한다`() {
        social.facebookMe("fb-token", json(fixture("facebook/me.json")))

        val response = login("FACEBOOK", "fb-token")

        assertEquals(200, response.statusCode.value(), "body=${response.body}")
        assertEquals("fsocial@snu.ac.kr", userRepository.findById(body(response)["userId"].asLong()).get().email)
    }

    @Test
    fun `페이스북 토큰이 만료돼 400 OAuthException이면 로그인 실패다`() {
        social.facebookMe("expired-token", json(fixture("facebook/error-190.json"), status = 400))

        assertProblem(login("FACEBOOK", "expired-token"), 401, "social-login-failed")
    }

    @Test
    fun `페이스북 Limited Login id_token을 JWKS로 검증해 가입한다`() {
        social.facebookJwks()
        val token = OidcTestIssuer.facebook.idToken("fb-oidc-sub", mapOf("name" to "Gildong Hong", "email" to "fboidc@snu.ac.kr"))

        val response = login("FACEBOOK", token)

        assertEquals(200, response.statusCode.value(), "body=${response.body}")
        assertEquals("fboidc@snu.ac.kr", userRepository.findById(body(response)["userId"].asLong()).get().email)
    }

    // --- Apple ---

    @Test
    fun `애플 transfer sub가 바뀌면 같은 계정으로 재연결된다`() {
        social.appleJwks()
        val apple = OidcTestIssuer.apple
        val first = login("APPLE", apple.idToken("apple-sub-1", mapOf("email" to "applesocial@snu.ac.kr", "transfer_sub" to "transfer-1")))
        assertEquals(200, first.statusCode.value(), "body=${first.body}")
        val userId = body(first)["userId"].asLong()

        val relogin =
            login("APPLE", apple.idToken("apple-sub-2", mapOf("email" to "applesocial@snu.ac.kr", "transfer_sub" to "transfer-1")))
        assertEquals(userId, body(relogin)["userId"].asLong())

        val again = login("APPLE", apple.idToken("apple-sub-2", mapOf("email" to "applesocial@snu.ac.kr")))
        assertEquals(userId, body(again)["userId"].asLong())
    }

    @Test
    fun `애플 토큰의 aud·iss가 다르거나 만료됐으면 로그인 실패다`() {
        social.appleJwks()
        val apple = OidcTestIssuer.apple

        assertProblem(login("APPLE", apple.idToken("apple-sub", audience = "other-app")), 401, "social-login-failed")
        assertProblem(login("APPLE", apple.idToken("apple-sub", issuer = "https://evil.example.com")), 401, "social-login-failed")
        assertProblem(login("APPLE", apple.idToken("apple-sub", expiresIn = Duration.ofMinutes(-5))), 401, "social-login-failed")
        assertEquals(0, userRepository.count())
    }

    @Test
    fun `JWKS에 없는 키로 서명한 애플 토큰은 로그인 실패다`() {
        social.appleJwks()
        val forged = OidcTestIssuer.apple.rotatedKey("forged-key")

        assertProblem(login("APPLE", OidcTestIssuer.apple.idToken("apple-sub", signingKey = forged)), 401, "social-login-failed")
        // 캐시에 없는 kid라 JWKS를 다시 받아 본다.
        assertEquals(1, social.requestsTo(APPLE_JWKS))
    }

    @Test
    fun `애플이 키를 교체하면 JWKS를 다시 받아 검증한다`() {
        val apple = OidcTestIssuer.apple
        val rotated = apple.rotatedKey("rotated-key")
        social.appleJwks(json(apple.jwks(apple.key, rotated)))

        val response = login("APPLE", apple.idToken("apple-rotated-sub", mapOf("email" to "rotated@snu.ac.kr"), signingKey = rotated))

        assertEquals(200, response.statusCode.value(), "body=${response.body}")
        assertEquals(1, social.requestsTo(APPLE_JWKS))
    }

    @Test
    fun `애플 JWKS가 5xx면 502로 제공자 장애를 알린다`() {
        social.appleJwks(status(500))
        val unseen = OidcTestIssuer.apple.rotatedKey("unseen-key")

        assertProblem(login("APPLE", OidcTestIssuer.apple.idToken("apple-sub", signingKey = unseen)), 502, "social-provider-unavailable")
    }

    @Test
    fun `JWT 모양이 아닌 애플 토큰은 JWKS를 부르지 않고 거절한다`() {
        assertProblem(login("APPLE", "not-a-jwt"), 401, "invalid-apple-login-token")
        assertEquals(0, social.requestsTo(APPLE_JWKS))
    }
}
