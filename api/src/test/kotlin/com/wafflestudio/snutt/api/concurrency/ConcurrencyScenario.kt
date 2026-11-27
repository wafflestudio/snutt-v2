package com.wafflestudio.snutt.api.concurrency

import com.wafflestudio.snutt.core.test.concurrency.ConcurrentOutcome
import com.wafflestudio.snutt.core.test.concurrency.LockContention
import com.wafflestudio.snutt.core.test.concurrency.runConcurrently
import org.springframework.transaction.TransactionDefinition

/**
 * 동시성 시나리오 하나. [ConcurrencyScenarios]의 팩토리 메서드가 DB 상태를 준비하고 이 객체를 돌려준다.
 *
 * - [request]는 요청 하나(= HTTP 요청 하나)를 서비스 호출로 보낸다. index는 0..requests-1이다.
 * - [verify]는 모든 요청이 끝난 뒤 성공·실패 수와 최종 DB 상태로 불변식을 검증한다.
 *
 * PR CI는 [runContended]로 race를 매번 같은 순서로 재현한다. stress 작업은 같은 시나리오를 큰 requests로 만들어
 * [runAllAtOnce]로 돌리거나 [request]를 HTTP 호출로 바꿔 재사용한다.
 */
interface ConcurrencyScenario {
    val requests: Int

    /** 서비스 트랜잭션의 격리 수준. holder 트랜잭션을 여기에 맞춘다. */
    val isolation: Int get() = TransactionDefinition.ISOLATION_DEFAULT

    fun request(index: Int)

    fun verify(outcome: ConcurrentOutcome<Unit>)
}

/** 요청 0이 락을 쥔 채 멈춘 동안 나머지 요청을 동시에 보낸다. [LockContention.holdThenContend] 참고. */
fun ConcurrencyScenario.runContended(contention: LockContention): ConcurrentOutcome<Unit> =
    contention
        .holdThenContend(requests - 1, isolation, holder = { request(0) }, contender = ::request)
        .also(::verify)

/** 모든 요청을 latch 하나로 동시에 출발시킨다. 인터리빙은 스케줄러에 맡긴다. */
fun ConcurrencyScenario.runAllAtOnce(): ConcurrentOutcome<Unit> = runConcurrently(requests, action = ::request).also(::verify)
