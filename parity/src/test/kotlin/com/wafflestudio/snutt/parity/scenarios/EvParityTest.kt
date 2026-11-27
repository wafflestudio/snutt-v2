package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityTest
import com.wafflestudio.snutt.parity.AbstractParityTest
import org.junit.jupiter.api.Test
import java.net.URLEncoder

/**
 * P1: 강의평. 구 앱은 강의 상세에서 `/v1/ev/lectures/{id}/summary`를, 강의평 웹뷰는 SNUTT 프록시 경로
 * `/ev-service/v1/...`를 부른다(v1 SNUTT가 EV로 프록시, v2는 v1compat이 직접 처리).
 */
@ParityTest
class EvParityTest : AbstractParityTest() {
    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    @Test
    fun `강의 상세에서 강의평 요약을 본다`() =
        parity("강의평 요약(앱)") {
            get("/v1/ev/lectures/@{lecture:os}/summary", `as` = "alice")
            get("/v1/ev/lectures/@{lecture:ds}/summary", `as` = "alice")
            get("/v1/ev/lectures/@{lecture:online}/summary", `as` = "alice", label = "EV 과목 없는 강의")
        }

    @Test
    fun `강의평 웹에서 둘러본다`() =
        parity("강의평 웹: 조회") {
            get("/ev-service/v1/tags/main", `as` = "alice")
            get("/ev-service/v1/tags/search", `as` = "alice")
            get("/ev-service/v1/lectures?query=${enc("운영")}", `as` = "alice")
            get("/ev-service/v1/lectures?query=", `as` = "alice", label = "빈 검색어")
            get("/ev-service/v1/lectures/id?course_number=M1522.000800&instructor=${enc("김운영")}", `as` = "alice")
            get("/ev-service/v1/lectures/@{course:os}/evaluation-summary", `as` = "alice")
            get("/ev-service/v1/lectures/@{course:os}/semester-lectures", `as` = "alice")
            get("/ev-service/v1/lectures/@{course:os}/evaluations", `as` = "alice", label = "첫 페이지(cursor 없음)")
            get("/ev-service/v1/lectures/@{course:os}/evaluations/users/me", `as` = "bob")
            get("/ev-service/v1/evaluations/users/me", `as` = "alice")
            get("/ev-service/v1/evaluations/@{evaluation:bob-os}", `as` = "alice")
            get("/ev-service/v1/tags/main/1/evaluations", `as` = "alice", label = "최신")
            get("/ev-service/v1/users/me/lectures/latest", `as` = "alice")
            get("/ev-service/v1/lectures/@{course:linear}/evaluations", `as` = "alice", label = "snuev 강의평")
        }

    @Test
    fun `강의평을 쓰고 공감하고 신고한다`() =
        parity("강의평 웹: 쓰기") {
            get(
                "/ev-service/v1/lectures/@{course:ds}/semester-lectures",
                `as` = "bob",
            ).capture("semester:ds-autumn", "/semester_lectures/0/id")
            post(
                "/ev-service/v1/semester-lectures/@{semester:ds-autumn}/evaluations",
                """{"content":"자료구조 수업이 체계적이고 과제가 실력을 늘려 줍니다. 추천합니다.","grade_satisfaction":4.0,"teaching_skill":4.0,"gains":5.0,"life_balance":3.0,"rating":4.5}""",
                `as` = "bob",
            ).capture("evaluation:bob-ds", "/id")
            post(
                "/ev-service/v1/semester-lectures/@{semester:ds-autumn}/evaluations",
                """{"content":"같은 학기에 두 번째 강의평을 쓰려고 합니다. 되면 안 됩니다.","grade_satisfaction":4.0,"teaching_skill":4.0,"gains":5.0,"life_balance":3.0,"rating":4.5}""",
                `as` = "bob",
                label = "같은 학기 두 번째 강의평",
            )
            get("/ev-service/v1/lectures/@{course:ds}/evaluation-summary", `as` = "bob")
            post("/ev-service/v1/evaluations/@{evaluation:alice-ds}/likes", `as` = "bob")
            post("/ev-service/v1/evaluations/@{evaluation:alice-ds}/likes", `as` = "bob", label = "중복 공감")
            delete("/ev-service/v1/evaluations/@{evaluation:bob-os}/likes", `as` = "alice")
            post("/ev-service/v1/evaluations/@{evaluation:alice-ds}/report", """{"content":"부적절한 내용"}""", `as` = "bob")
            post("/ev-service/v1/evaluations/@{evaluation:alice-ds}/report", """{"content":"또 신고"}""", `as` = "bob", label = "중복 신고")
            patch(
                "/ev-service/v1/evaluations/@{evaluation:bob-ds}",
                """{"content":"수정한 강의평입니다. 과제가 많지만 그만큼 배울 것이 많습니다.","grade_satisfaction":3.0,"teaching_skill":4.0,"gains":4.0,"life_balance":2.0,"rating":4.0}""",
                `as` = "bob",
            )
            delete("/ev-service/v1/evaluations/@{evaluation:bob-ds}", `as` = "alice", label = "남의 강의평 삭제")
            delete("/ev-service/v1/evaluations/@{evaluation:bob-ds}", `as` = "bob")
            get("/ev-service/v1/lectures/@{course:ds}/evaluations", `as` = "bob")
        }

    // B1: v1compat이 없는 과목·분반의 errcode를 서로 바꿔 내던 버그(작업 D가 고침).
    @Test
    fun `없는 과목과 분반의 에러코드`() =
        parity("강의평 웹: 없는 과목·분반") {
            get("/ev-service/v1/lectures/999999/evaluation-summary", `as` = "alice", label = "없는 과목")
            post(
                "/ev-service/v1/semester-lectures/999999/evaluations",
                """{"content":"없는 분반에 쓰는 강의평입니다. 에러 코드를 확인합니다.","grade_satisfaction":4.0,"teaching_skill":4.0,"gains":4.0,"life_balance":4.0,"rating":4.0}""",
                `as` = "alice",
                label = "없는 분반",
            )
        }

    // B3: isEmailVerified 필드가 없던 레거시 계정을 미인증으로 막던 버그(작업 D가 고침).
    @Test
    fun `이메일 인증 기록이 없는 계정이 강의평을 본다`() =
        parity("강의평: isEmailVerified 없는 계정(carol)") {
            get("/ev-service/v1/tags/main", `as` = "carol")
            get("/v1/ev/lectures/@{lecture:os}/summary", `as` = "carol")
        }

    // B6: 세부 점수가 NULL인 강의평(snuev 출처)의 점수를 v1처럼 0.0으로 낸다(v1compat EV 매퍼).
    @Test
    fun `세부 점수가 없는 강의평`() =
        parity("강의평 웹: 세부 점수 없는 강의평") {
            get("/ev-service/v1/lectures/@{course:linear}/evaluations", `as` = "alice")
        }

    // B5b: 빈 cursor는 v1 PageUtils.getCursor처럼 첫 페이지로 본다.
    @Test
    fun `강의평 첫 페이지에 빈 cursor`() =
        parity("강의평 웹: 빈 cursor") {
            get("/ev-service/v1/lectures/@{course:os}/evaluations?cursor=", `as` = "alice", label = "과목 강의평")
            get("/ev-service/v1/evaluations/users/me?cursor=", `as` = "alice", label = "내 강의평")
            get("/ev-service/v1/tags/main/1/evaluations?cursor=", `as` = "alice", label = "태그 강의평")
        }
}
