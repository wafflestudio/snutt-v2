package com.wafflestudio.snutt.core.test.concurrency

import com.wafflestudio.snutt.core.test.IntegrationContainers
import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 락이 race를 막는다는 것을 매번 같은 순서로 재현한다. 스케줄링 운에 기대지 않는다.
 *
 * 1. holder가 트랜잭션을 열고 holder 동작(서비스 호출 한 번)을 끝낸 뒤 flush하고 커밋하지 않고 멈춘다.
 *    이때 holder는 서비스가 잡은 락(FOR UPDATE, insert·update한 행)을 쥐고 있다.
 * 2. contender N개를 동시에 출발시킨다.
 * 3. contender 각각이 InnoDB 락 대기(`performance_schema.data_lock_waits`)에 들어가거나 끝나면 holder를 커밋한다.
 *
 * 락이 있으면 contender는 holder 커밋을 기다렸다가 holder가 만든 상태를 보고 판단한다.
 * 락이 없거나 락보다 먼저 스냅숏을 읽으면 contender는 holder가 커밋하기 전 상태로 판단하므로 결과 불변식이 깨진다.
 *
 * 결과의 0번은 holder, 1..N은 contender다. 서비스 트랜잭션 격리 수준이 기본값이 아니면 [isolation]을 같게 맞춘다
 * (바깥 트랜잭션에 참여한 @Transactional의 isolation은 무시된다).
 * holder와 contender가 동시에 커넥션 N+1개를 쓰므로 N은 Hikari 풀(기본 10)보다 충분히 작게 둔다.
 * 락 대기는 테스트 컨테이너의 root 계정으로 따로 연결해 센다(performance_schema 조회 권한이 필요하다).
 * 첫 잠금을 기다리는 트랜잭션은 INNODB_TRX에 보이지 않으므로 data_lock_waits를 쓴다.
 */
class LockContention(
    private val transactionManager: PlatformTransactionManager,
    private val entityManagerFactory: EntityManagerFactory,
) {
    fun <T> holdThenContend(
        contenders: Int,
        isolation: Int = TransactionDefinition.ISOLATION_DEFAULT,
        timeout: Duration = DEFAULT_CONCURRENCY_TIMEOUT,
        holder: () -> T,
        contender: (index: Int) -> T,
    ): ConcurrentOutcome<T> {
        val holding = CountDownLatch(1)
        val finished = AtomicInteger()
        val transaction = TransactionTemplate(transactionManager).apply { isolationLevel = isolation }
        val executor = Executors.newSingleThreadExecutor()
        rootConnection().use { probe ->
            try {
                val holderFuture =
                    executor.submit<Result<T>> {
                        runCatching {
                            transaction.execute {
                                val result =
                                    try {
                                        holder().also {
                                            EntityManagerFactoryUtils
                                                .getTransactionalEntityManager(
                                                    entityManagerFactory,
                                                )?.flush()
                                        }
                                    } finally {
                                        holding.countDown()
                                    }
                                pollUntil(timeout, { "contender $contenders 개 중 대기 ${lockWaits(probe)}, 종료 ${finished.get()}" }) {
                                    lockWaits(probe) + finished.get() >= contenders
                                }
                                result
                            }
                        }
                    }
                if (!holding.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw AssertionError("holder가 $timeout 안에 끝나지 않았다")
                val contended =
                    runConcurrently(contenders, timeout) {
                        try {
                            contender(it + 1)
                        } finally {
                            finished.incrementAndGet()
                        }
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
    }

    private fun rootConnection(): Connection {
        val mysql = IntegrationContainers.mysql
        return DriverManager.getConnection(mysql.jdbcUrl, "root", mysql.password)
    }

    private fun lockWaits(probe: Connection): Int =
        probe.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits").use {
                it.next()
                it.getInt(1)
            }
        }
}
