package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.api.testutil.legacyApiKey
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import io.jsonwebtoken.JwtBuilder
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.time.Instant
import java.util.Date
import javax.crypto.spec.SecretKeySpec

/**
 * 전환 당일 구앱의 로그인 유지와, 신앱으로 업그레이드하는 순간(레거시 토큰 → v2 토큰 교환).
 * 구앱은 v1이 발급한 `credentialHash`를 `x-access-token`에, 빌드에 박힌 API key JWT를 `x-access-apikey`에 보낸다.
 * 0x2001·0x2002·0x2000을 받으면 구앱은 강제 로그아웃한다(iOS AuthenticationMiddleware, Android ExceptionMapper).
 */
class LegacySessionScenarioTest : AbstractV1CompatTest() {
    @Autowired
    lateinit var userRepository: UserRepository

    private fun apiKey(
        secret: String = "test-legacy-secret-key-0123456789abcdef",
        configure: JwtBuilder.() -> Unit,
    ): String =
        Jwts
            .builder()
            .claim("string", "android")
            .claim("key_version", "0")
            .apply(configure)
            .signWith(SecretKeySpec(secret.toByteArray(), "HmacSHA256"), Jwts.SIG.HS256)
            .compact()

    private fun callMe(
        token: String?,
        apiKey: String? = legacyApiKey("android"),
    ) = v1Send(HttpMethod.GET, "/v1/users/me", legacyToken = token, apiKey = apiKey)

    @Test
    fun `이관된 credentialHash 토큰으로 구앱이 로그인 상태를 유지한다`() {
        val migrated = migratedUser("keepalive")
        val user = userRepository.findById(migrated.id).get()
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val timetable = fixtures.timetable(user, autumn, title = "이관된 시간표")

        val me = callMe(migrated.token)
        assertEquals(200, me.statusCode.value(), "body=${me.body}")
        assertEquals(migrated.id.toString(), body(me)["id"].asString())
        assertEquals("keepalive", body(me)["nickname"]["nickname"].asString())
        assertEquals("0001", body(me)["nickname"]["tag"].asString())

        val tables = body(v1Get("/v1/tables", migrated.token))
        assertEquals(listOf(timetable.id.toString()), tables.items().map { it["_id"].asString() })
        assertEquals(200, v1Get("/v1/notification/count", migrated.token).statusCode.value())
    }

    @Test
    fun `토큰이 없거나 위조됐거나 탈퇴한 사용자면 구앱이 강제 로그아웃하는 코드로 응답한다`() {
        val withdrawn = migratedUser("withdrawn", active = false)
        val (withdrawingToken, _) = v1Register("withdrawing")

        assertV1Error(callMe(null), 401, 0x2001)
        assertV1Error(callMe(sha256Hex("forged")), 403, 0x2002)
        assertV1Error(callMe(withdrawn.token), 403, 0x2002)

        assertEquals(200, v1Delete("/v1/user/account", withdrawingToken).statusCode.value())
        assertV1Error(callMe(withdrawingToken), 403, 0x2002)
    }

    @Test
    fun `구앱 API key는 서명·버전·플랫폼·유효기간을 v1처럼 검사한다`() {
        val migrated = migratedUser("apikeyuser")
        clock.setInstant(Instant.parse("2026-09-01T00:00:00Z"))

        assertEquals(200, callMe(migrated.token, apiKey { }).statusCode.value())
        assertEquals(
            200,
            callMe(migrated.token, apiKey { expiration(Date.from(Instant.parse("2026-09-01T00:00:01Z"))) }).statusCode.value(),
        )

        assertV1Error(callMe(migrated.token, apiKey(secret = "another-secret-key-0123456789abcdef!!") { }), 403, 0x2000)
        assertV1Error(callMe(migrated.token, apiKey { claim("key_version", "1") }), 403, 0x2000)
        assertV1Error(callMe(migrated.token, apiKey { claim("string", "desktop") }), 403, 0x2000)
        assertV1Error(callMe(migrated.token, apiKey { expiration(Date.from(Instant.parse("2026-08-31T23:59:59Z"))) }), 403, 0x2000)
        assertV1Error(callMe(migrated.token, apiKey { notBefore(Date.from(Instant.parse("2026-09-01T00:00:01Z"))) }), 403, 0x2000)
        assertV1Error(callMe(migrated.token, apiKey = null), 403, 0x2000)
        // v2 플랫폼 키(x-client-key)만으로는 v1 경로를 통과하지 못한다
        assertV1Error(
            v1Send(
                HttpMethod.GET,
                "/v1/users/me",
                legacyToken = migrated.token,
                apiKey = null,
                headers =
                    mapOf(
                        "x-client-key" to "test-ios-key",
                    ),
            ),
            403,
            0x2000,
        )
    }

    @Test
    fun `자격증명이 바뀌면 구앱 토큰은 모두 무효가 되고 새 토큰만 통한다`() {
        val (firstDevice, _) = v1Register("credchange")
        val secondDevice = body(v1Post("/v1/auth/login_local", """{"id":"credchange","password":"password1"}"""))["token"].asString()
        assertNotEquals(firstDevice, secondDevice)

        val changed = v1Put("/v1/user/password", """{"old_password":"password1","new_password":"password2"}""", firstDevice)
        assertEquals(200, changed.statusCode.value(), "body=${changed.body}")
        val reissued = body(changed)["token"].asString()

        assertV1Error(callMe(firstDevice), 403, 0x2002)
        assertV1Error(callMe(secondDevice), 403, 0x2002)
        assertEquals(200, callMe(reissued).statusCode.value())

        // 신앱에서 비밀번호를 바꿔도 구앱 토큰은 무효가 된다
        val v2Token = v2Login("credchange", "password2")
        val v2Changed = patch("/v2/users/me/password", """{"currentPassword":"password2","newPassword":"password3"}""", v2Token)
        assertEquals(200, v2Changed.statusCode.value(), "body=${v2Changed.body}")
        assertV1Error(callMe(reissued), 403, 0x2002)
    }

    @Test
    fun `신앱으로 업그레이드하면 레거시 토큰을 v2 토큰으로 바꾸고 같은 데이터를 본다`() {
        val migrated = migratedUser("upgrader")
        val user = userRepository.findById(migrated.id).get()
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val timetable = fixtures.timetable(user, autumn, title = "업그레이드 전 시간표")

        val exchanged = v1Post("/v1/auth/token/exchange", """{"legacyToken":"${migrated.token}"}""")
        assertEquals(200, exchanged.statusCode.value(), "body=${exchanged.body}")
        val tokens = body(exchanged)
        assertEquals(migrated.id.toString(), tokens["userId"].asString())
        val accessToken = tokens["accessToken"].asString()

        assertEquals(migrated.id, body(get("/v2/users/me", accessToken))["id"].asLong())
        assertEquals(
            listOf(timetable.id),
            body(get("/v2/timetables", accessToken)).items().map { it["id"].asLong() },
        )
        val refreshed = post("/v2/auth/refresh", """{"refreshToken":"${tokens["refreshToken"].asString()}"}""")
        assertEquals(200, refreshed.statusCode.value(), "body=${refreshed.body}")
        assertEquals(migrated.id, body(get("/v2/users/me", body(refreshed)["accessToken"].asString()))["id"].asLong())

        // 교환은 레거시 토큰을 지우지 않는다(V1CompatAuthController.exchangeLegacyToken). 같은 기기의 구앱이나
        // 아직 업그레이드하지 않은 다른 기기가 계속 쓸 수 있고, 같은 토큰으로 다시 교환할 수도 있다.
        assertEquals(200, callMe(migrated.token).statusCode.value())
        assertEquals(200, v1Post("/v1/auth/token/exchange", """{"legacyToken":"${migrated.token}"}""").statusCode.value())
        // v2 토큰은 구앱 토큰 자리에서 쓸 수 없다
        assertV1Error(callMe(accessToken), 403, 0x2002)
    }

    @Test
    fun `교환할 수 없는 레거시 토큰은 v1 에러 형식으로 거절한다`() {
        val withdrawn = migratedUser("exchangewithdrawn", active = false)

        assertV1Error(v1Post("/v1/auth/token/exchange", """{"legacyToken":"${sha256Hex("forged")}"}"""), 403, 0x2002)
        assertV1Error(v1Post("/v1/auth/token/exchange", """{"legacyToken":"${withdrawn.token}"}"""), 403, 0x2002)
        assertV1Error(v1Post("/v1/auth/token/exchange", """{}"""), 400, 40002)
        assertV1Error(
            v1Send(HttpMethod.POST, "/v1/auth/token/exchange", """{"legacyToken":"${withdrawn.token}"}""", apiKey = null),
            403,
            0x2000,
        )
    }
}
