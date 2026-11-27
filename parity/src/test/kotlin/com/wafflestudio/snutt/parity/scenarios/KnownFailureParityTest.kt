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
