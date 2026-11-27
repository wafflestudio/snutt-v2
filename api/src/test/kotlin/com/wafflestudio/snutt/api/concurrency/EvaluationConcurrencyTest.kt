package com.wafflestudio.snutt.api.concurrency

import org.junit.jupiter.api.Test

/** course 행 잠금과 evaluation 행 잠금이 막는 race. 강의평 서비스는 READ COMMITTED로 돈다. */
class EvaluationConcurrencyTest : AbstractConcurrencyTest() {
    @Test
    fun `같은 사용자가 같은 강의평을 동시에 쓰면 하나만 저장되고 집계도 1개다`() {
        scenarios.sameUserEvaluatesTwice(REQUESTS).runContended(contention)
    }

    @Test
    fun `여러 사용자가 동시에 강의평을 쓰면 course 집계가 실제 강의평과 같다`() {
        scenarios.differentUsersEvaluateOneCourse(REQUESTS).runContended(contention)
    }

    @Test
    fun `강의평 작성과 삭제가 겹쳐도 course 집계가 보이는 강의평과 같다`() {
        scenarios.evaluationsWrittenAndDeletedTogether(REQUESTS).runContended(contention)
    }

    @Test
    fun `같은 사용자가 공감을 동시에 누르면 공감은 하나이고 like_count도 1이다`() {
        scenarios.sameUserLikesTwice(REQUESTS).runContended(contention)
    }

    @Test
    fun `공감과 공감 취소가 겹쳐도 like_count가 공감 행 수와 같다`() {
        scenarios.likesAndCancelsTogether(REQUESTS).runContended(contention)
    }
}
