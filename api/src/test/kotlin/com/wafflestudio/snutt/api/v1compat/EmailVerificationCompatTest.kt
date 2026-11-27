package com.wafflestudio.snutt.api.v1compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * v1 `User.isEmailVerified`는 Boolean?이었다. 필드가 생기기 전 계정은 null이고,
 * v1 강의평 프록시는 `== false`일 때만 막았다(snutt/core/.../EvService.kt:46). 인증 여부 조회는 `?: false`였다.
 * 이관은 null을 보존하고, 구앱(v1compat)은 v1과 같게, 신앱(v2)은 인증된 사용자만 강의평을 쓰게 한다.
 */
class EmailVerificationCompatTest : AbstractV1CompatTest() {
    @Test
    fun `인증 여부가 없던 이관 사용자는 구앱에서 강의평을 쓸 수 있다`() {
        val unset = migratedUser("unsetuser", isEmailVerified = null)
        val unverified = migratedUser("falseuser", isEmailVerified = false)

        assertEquals(200, v1Get("/v1/ev-service/v1/evaluations/users/me", unset.token).statusCode.value())
        assertEquals(200, v1Get("/ev-service/v1/tags/main", unset.token).statusCode.value())
        assertV1Error(v1Get("/v1/ev-service/v1/evaluations/users/me", unverified.token), 403, 0x3011)
    }

    @Test
    fun `인증 여부가 없던 사용자도 인증 상태 조회에서는 미인증이다`() {
        val unset = migratedUser("unsetstatus", isEmailVerified = null)

        assertEquals(false, body(v1Get("/v1/user/email/verification", unset.token))["is_email_verified"].asBoolean())

        val exchanged = body(v1Post("/v1/auth/token/exchange", """{"legacyToken":"${unset.token}"}"""))
        val v2Token = exchanged["accessToken"].asString()
        assertEquals(false, body(get("/v2/users/me", v2Token))["isEmailVerified"].asBoolean())
        // 신앱은 v2 정책대로 인증된 사용자만 강의평을 쓴다.
        assertEquals(403, get("/v2/courses", v2Token).statusCode.value())
    }

    @Test
    fun `인증 여부가 없던 사용자가 인증을 해제하면 v1처럼 강의평이 막힌다`() {
        val unset = migratedUser("unsetreset", isEmailVerified = null)

        val reset = v1Delete("/v1/user/email/verification", unset.token)
        assertEquals(200, reset.statusCode.value(), "body=${reset.body}")

        assertV1Error(v1Get("/v1/ev-service/v1/evaluations/users/me", unset.token), 403, 0x3011)
    }
}
