package com.wafflestudio.snutt.core.test.concurrency

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 락이 race를 막는다는 것을 매번 같은 순서로 재현한다. 스케줄링 운에 기대지 않는다.
 *
 * 1. holder가 트랜잭션을 열고 holder 동작(서비스 호출 한 번)을 끝낸 뒤 커밋하지 않고 멈춘다. 이때 holder는 서비스가 잡는 락을 쥐고 있다.
 * 2. contender N개를 동시에 출발시킨다.
 * 3. contender N개가 모두 DB 안에서 대기(processlist에서 실행 중인 쿼리)하거나 이미 끝나면 holder를 커밋한다.
 *
 * 락이 있으면 contender는 holder 커밋을 기다렸다가 holder가 만든 상태를 보고 판단한다.
 * 락이 없거나 락보다 먼저 스냅숏을 읽으면 contender는 holder가 커밋하기 전 상태로 판단하므로 결과 불변식이 깨진다.
 *
 * 결과의 0번은 holder, 1..N은 contender다. 서비스 트랜잭션 격리 수준이 기본값이 아니면 [isolation]을 같게 맞춘다
 * (바깥 트랜잭션에 참여한 @Transactional의 isolation은 무시된다).
 * holder와 contender가 동시에 커넥션 N+1개를 쓰므로 N은 Hikari 풀(기본 10)보다 충분히 작게 둔다.
 */
class LockContention(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
) {
    fun <T> holdThenContend(
        contenders: Int,
        isolation: Int = TransactionDefinition.ISOLATION_DEFAULT,
        timeout: Duration = DEFAULT_CONCURRENCY_TIMEOUT,
        holder: () -> T,
        contender: (index: Int) -> T,
    ): ConcurrentOutcome<T> {
        val holding = CountDownLatch(1)
        val contendersDone = AtomicBoolean(false)
        val transaction = TransactionTemplate(transactionManager).apply { isolationLevel = isolation }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val holderFuture =
                executor.submit<Result<T>> {
                    runCatching {
                        transaction.execute {
                            val result =
                                try {
                                    holder()
                                } finally {
                                    holding.countDown()
                                }
                            pollUntil(timeout, { "contender $contenders 개가 holder 락을 기다리지 않았다(대기 중 ${waitingQueries()})" }) {
                                contendersDone.get() || waitingQueries() >= contenders
                            }
                            result
                        }
                    }
                }
            if (!holding.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw AssertionError("holder가 $timeout 안에 끝나지 않았다")
            val contended =
                try {
                    runConcurrently(contenders, timeout) { contender(it + 1) }
                } finally {
                    contendersDone.set(true)
                }
            val holderResult =
                try {
                    holderFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    throw AssertionError("holder 커밋이 $timeout 안에 끝나지 않았다", e)
                }
            return ConcurrentOutcome(listOf(holderResult) + contended.results)
        } finally {
            executor.shutdownNow()
        }
    }

    /** holder 커넥션에서 실행한다. 같은 DB 사용자의 다른 커넥션 중 쿼리를 실행 중(=락 대기 포함)인 것의 수. */
    private fun waitingQueries(): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.PROCESSLIST " +
                "WHERE COMMAND = 'Query' AND ID <> CONNECTION_ID() AND DB = DATABASE()",
            Int::class.java,
        )!!
}
