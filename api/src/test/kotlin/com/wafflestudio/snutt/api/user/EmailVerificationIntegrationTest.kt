package com.wafflestudio.snutt.api.user

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.testutil.legacyApiKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class EmailVerificationIntegrationTest : AbstractApiIntegrationTest() {
    private fun sentCodeTo(email: String): String {
        val subject = argumentCaptor<String>()
        verify(mailClient).send(eq(email), subject.capture(), any())
        return Regex("\\[(\\d{6})]").find(subject.firstValue)!!.groupValues[1]
    }

    @Test
    fun `SNU 메일이 아니면 인증 코드를 발송하지 않는다`() {
        val token = register("emailuser", "temp@snu.ac.kr")
        val response = post("/v2/users/me/email/verification", """{"email":"foo@gmail.com"}""", token)
        assertEquals(400, response.statusCode.value())
        verify(mailClient, never()).send(any(), any(), any())
    }

    @Test
    fun `인증 코드 발송과 검증과 리셋`() {
        val token = register("emailuser", "temp@snu.ac.kr")
        val send = post("/v2/users/me/email/verification", """{"email":"emailuser@snu.ac.kr"}""", token)
        assertEquals(200, send.statusCode.value())
        val code = sentCodeTo("emailuser@snu.ac.kr")

        val wrong = post("/v2/users/me/email/verification/code", """{"code":"000000"}""", token)
        assertEquals(400, wrong.statusCode.value())

        val verified = post("/v2/users/me/email/verification/code", """{"code":"$code"}""", token)
        assertEquals(200, verified.statusCode.value())
        assertEquals(true, body(verified)["isEmailVerified"].asBoolean())

        val again = post("/v2/users/me/email/verification", """{"email":"emailuser@snu.ac.kr"}""", token)
        assertEquals(400, again.statusCode.value())

        val reset = delete("/v2/users/me/email/verification", token)
        assertEquals(false, body(reset)["isEmailVerified"].asBoolean())
        assertEquals(false, body(get("/v2/users/me/email/verification", token))["isEmailVerified"].asBoolean())
    }

    @Test
    fun `v1 경로에서도 이메일 인증이 동작한다`() {
        val registered =
            client()
                .post()
                .uri("/v1/auth/register_local")
                .header("x-access-apikey", legacyApiKey())
                .body("""{"id":"v1emailuser","password":"password1","email":"v1temp@snu.ac.kr"}""")
                .retrieve()
                .toEntity(String::class.java)
        val v1Token = body(registered)["token"].asString()

        val send =
            client()
                .post()
                .uri("/v1/user/email/verification")
                .header("x-access-apikey", legacyApiKey())
                .header("x-access-token", v1Token)
                .body("""{"email":"v1email@snu.ac.kr"}""")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, send.statusCode.value())
        val code = sentCodeTo("v1email@snu.ac.kr")

        val verified =
            client()
                .post()
                .uri("/v1/user/email/verification/code")
                .header("x-access-apikey", legacyApiKey())
                .header("x-access-token", v1Token)
                .body("""{"code":"$code"}""")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, verified.statusCode.value())
        assertEquals(true, body(verified)["is_email_verified"].asBoolean())
    }
}
