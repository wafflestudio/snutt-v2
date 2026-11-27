package com.wafflestudio.snutt.api.concurrency

import org.junit.jupiter.api.Test

/** 사용자 단위 요청의 race. unique 제약 또는 사용자 행 잠금이 막는다. */
class AccountConcurrencyTest : AbstractConcurrencyTest() {
    @Test
    fun `같은 localId로 동시에 가입하면 하나만 가입되고 나머지는 DUPLICATE_LOCAL_ID다`() {
        scenarios.signUpWithSameLocalId(REQUESTS).runContended(contention)
    }

    @Test
    fun `같은 강의를 동시에 관심강좌에 넣어도 오류 없이 하나만 저장된다`() {
        scenarios.bookmarkSameLecture(REQUESTS).runContended(contention)
    }
}
