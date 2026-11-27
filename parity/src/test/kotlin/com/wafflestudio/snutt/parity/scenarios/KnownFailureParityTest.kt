package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityKnownFailure
import com.wafflestudio.snutt.parity.AbstractParityTest
import org.junit.jupiter.api.Test

/**
 * parity가 잡은 실제 차이(버그). 고쳐지기 전까지 실패하므로 `parityTest`에서 빠지고 `./gradlew parityKnownFailureTest`로만 돈다.
 * 각 시나리오에는 그 버그를 드러내는 요청만 둔다. 고친 뒤에는 해당 시나리오를 [com.wafflestudio.snutt.core.test.ParityTest]
 * 클래스로 옮긴다. 버그 목록과 근거는 docs/parity.md "알려진 차이(버그)".
 */
@ParityKnownFailure
class KnownFailureParityTest : AbstractParityTest() {
    override val tolerateKnownFailures = false

    @Test
    fun `EV 과목·분반 없음 에러코드`() =
        parity("버그: EV 24001/24002 뒤바뀜") {
            get("/ev-service/v1/lectures/999999/evaluation-summary", `as` = "alice", label = "없는 과목")
            post(
                "/ev-service/v1/semester-lectures/999999/evaluations",
                """{"content":"없는 분반에 쓰는 강의평입니다. 에러 코드를 확인합니다.","grade_satisfaction":4.0,"teaching_skill":4.0,"gains":4.0,"life_balance":4.0,"rating":4.0}""",
                `as` = "alice",
                label = "없는 분반",
            )
        }

    @Test
    fun `저장한 적 없는 푸시 설정`() =
        parity("버그: 푸시 설정 기본값") {
            get("/v1/push/preferences", `as` = "bob")
        }

    @Test
    fun `isEmailVerified가 없던 계정의 강의평 접근`() =
        parity("버그: 이메일 인증 null 계정") {
            get("/ev-service/v1/tags/main", `as` = "carol")
            get("/v1/ev/lectures/@{lecture:os}/summary", `as` = "carol")
        }

    @Test
    fun `v1 경로의 처리되지 않은 예외와 없는 경로`() =
        parity("버그: 레거시 에러 바디가 아닌 응답") {
            get("/v1/no-such-endpoint", `as` = "alice", label = "없는 경로")
            patch("/v1/tables", "{}", `as` = "alice", label = "지원하지 않는 메서드")
        }

    @Test
    fun `강의평 응답의 user_id 치환`() =
        parity("버그: EV user_id → user") {
            get("/ev-service/v1/evaluations/@{evaluation:bob-os}", `as` = "alice")
        }

    @Test
    fun `대표 시간표를 바꾸면 이전 대표의 수정 시각이 바뀐다`() =
        parity("버그: 대표 해제 시 updated_at 변경") {
            post("/v1/tables/@{tt:alice-plan-b}/primary", `as` = "alice")
            get("/v1/tables/@{tt:alice-main}", `as` = "alice")
        }

    @Test
    fun `세부 점수가 없는 강의평`() =
        parity("버그: EV NULL 점수") {
            get("/ev-service/v1/lectures/@{course:linear}/evaluations", `as` = "alice")
        }

    @Test
    fun `강의평 첫 페이지에 빈 cursor`() =
        parity("확인: EV 빈 cursor") {
            get("/ev-service/v1/lectures/@{course:os}/evaluations?cursor=", `as` = "alice")
        }
}
