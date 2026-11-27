package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityTest
import com.wafflestudio.snutt.parity.AbstractParityTest
import org.junit.jupiter.api.Test

/** P2: 수강편람·학기 상태와 Android 웹뷰가 여는 정적 페이지. */
@ParityTest
class StaticPageParityTest : AbstractParityTest() {
    @Test
    fun `수강편람과 학기 상태를 받는다`() =
        parity("수강편람·학기") {
            get("/v1/course_books", `as` = "alice")
            get("/v1/course_books/official?year=2026&semester=3&course_number=M1522.000800&lecture_number=001", `as` = "alice")
            get("/v1/semesters/status", `as` = "alice")
            get("/v1/buildings?places=301-101", `as` = "alice")
        }

    @Test
    fun `정적 페이지를 연다`() =
        parity("정적 페이지") {
            get("/member")
            get("/privacy_policy")
            get("/terms_of_service")
        }
}
