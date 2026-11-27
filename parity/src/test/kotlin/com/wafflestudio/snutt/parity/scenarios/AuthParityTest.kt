package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityTest
import com.wafflestudio.snutt.parity.AbstractParityTest
import com.wafflestudio.snutt.parity.scenario.legacyApiKey
import org.junit.jupiter.api.Test

/** P0: 로그인·가입·인증 실패. 앱은 0x2001/0x2002/0x2000으로 강제 로그아웃한다. */
@ParityTest
class AuthParityTest : AbstractParityTest() {
    @Test
    fun `로컬 로그인과 실패 코드`() =
        parity("로그인: 로컬") {
            post("/v1/auth/login_local", """{"id":"alice","password":"password1"}""").capture("token:alice-new", "/token")
            get("/v1/users/me", `as` = "alice-new", label = "새 토큰으로 내 정보")
            post("/v1/auth/login_local", """{"id":"alice","password":"wrong-password"}""", label = "틀린 비밀번호")
            post("/v1/auth/login_local", """{"id":"nobody","password":"password1"}""", label = "없는 아이디")
            post("/v1/auth/login_local", """{"user_id":"bob","password":"password1"}""", label = "user_id 별칭")
        }

    @Test
    fun `같은 아이디를 쓰던 계정 둘 중 최근 로그인한 쪽으로 로그인된다`() =
        parity("로그인: 아이디 중복 계정(frank)") {
            post("/v1/auth/login_local", """{"id":"frank","password":"password1"}""")
            get("/v1/users/me", `as` = "frank-new")
            get("/v1/users/me", `as` = "frank-old")
        }

    @Test
    fun `가입하고 받은 토큰으로 다음 요청을 보낸다`() =
        parity("가입: 로컬") {
            post("/v1/auth/register_local", """{"id":"newbie","password":"password123","email":"newbie@snu.ac.kr"}""")
                .capture("token:newbie", "/token")
                .capture("user:newbie", "/user_id")
            get("/v1/users/me", `as` = "newbie")
            get("/v1/tables", `as` = "newbie")
            post("/v1/auth/register_local", """{"id":"newbie","password":"password123","email":"other@snu.ac.kr"}""", label = "중복 아이디")
            post("/v1/auth/register_local", """{"id":"newbie2","password":"password123","email":"alice@snu.ac.kr"}""", label = "중복 이메일")
            post("/v1/auth/register_local", """{"id":"x","password":"password123","email":"x@snu.ac.kr"}""", label = "짧은 아이디")
            post("/v1/auth/register_local", """{"id":"newbie3","password":"short","email":"n3@snu.ac.kr"}""", label = "짧은 비밀번호")
        }

    @Test
    fun `인증 실패 응답`() =
        parity("인증 실패") {
            get("/v1/users/me", label = "토큰 없음")
            get("/v1/users/me", headers = mapOf("x-access-token" to "not-a-token"), label = "틀린 토큰")
            get("/v1/users/me", `as` = "alice", headers = mapOf("x-access-apikey" to ""), label = "apikey 없음")
            get("/v1/users/me", `as` = "alice", headers = mapOf("x-access-apikey" to "garbage"), label = "틀린 apikey")
            get(
                "/v1/users/me",
                `as` = "alice",
                headers = mapOf("x-access-apikey" to legacyApiKey("ios", keyVersion = "1")),
                label = "key_version 불일치",
            )
            get("/v1/users/me", `as` = "erin", label = "탈퇴 계정 토큰")
        }

    @Test
    fun `두 계정이 공유하던 레거시 토큰`() =
        parity("인증: 공유 credentialHash(grace, heidi)") {
            get("/v1/users/me", `as` = "grace")
            get("/v1/tables", `as` = "grace")
        }

    // B4: v1 경로의 없는 경로·지원하지 않는 메서드가 ProblemDetail로 나가던 버그(작업 D가 고침).
    @Test
    fun `v1 경로의 없는 경로와 지원하지 않는 메서드`() =
        parity("레거시 에러 바디: 404·405") {
            get("/v1/no-such-endpoint", `as` = "alice", label = "없는 경로")
            patch("/v1/tables", "{}", `as` = "alice", label = "지원하지 않는 메서드")
        }
}
