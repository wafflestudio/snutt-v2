package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityTest
import com.wafflestudio.snutt.parity.AbstractParityTest
import com.wafflestudio.snutt.parity.scenario.legacyApiKey
import org.junit.jupiter.api.Test

/** P0: 이관된 레거시 토큰으로 구 앱을 켰을 때 부르는 엔드포인트(docs/v1-compat-audit.md "parity 테스트 시나리오 후보"). */
@ParityTest
class AppStartParityTest : AbstractParityTest() {
    @Test
    fun `로컬 계정 사용자가 앱을 시작한다`() =
        parity("앱 시작: 로컬 계정(alice)") {
            get("/v1/users/me", `as` = "alice")
            get("/v1/user/info", `as` = "alice")
            get("/v1/users/me/social_providers", `as` = "alice")
            get("/v1/tables", `as` = "alice")
            get("/v1/tables/recent", `as` = "alice")
            get("/v1/course_books", `as` = "alice")
            get("/v1/course_books/recent", `as` = "alice")
            get("/v1/semesters/status", `as` = "alice")
            get("/v1/configs", `as` = "alice")
            get("/v1/popups", `as` = "alice")
            get("/v1/notification/count", `as` = "alice")
            get("/v1/buildings?places=301-101,302-208", `as` = "alice")
        }

    @Test
    fun `소셜 계정 사용자가 앱을 시작한다`() =
        parity("앱 시작: 소셜 계정(bob, carol, dave)") {
            get("/v1/users/me", `as` = "bob")
            get("/v1/users/me/social_providers", `as` = "bob")
            get("/v1/users/me", `as` = "carol")
            get("/v1/users/me/social_providers", `as` = "carol")
            get("/v1/users/me", `as` = "dave")
            get("/v1/tables", `as` = "dave")
        }

    @Test
    fun `안드로이드 구 앱이 앱을 시작한다`() =
        parity("앱 시작: Android 헤더") {
            defaultHeaders =
                defaultHeaders +
                mapOf(
                    "x-access-apikey" to legacyApiKey("android"),
                    "x-os-type" to "android",
                    "x-app-version" to "3.12.0",
                )
            get("/v1/users/me", `as` = "alice")
            get("/v1/configs", `as` = "alice")
            get("/v1/tables/recent", `as` = "alice")
        }
}
