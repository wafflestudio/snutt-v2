package com.wafflestudio.snutt.core.test.concurrency

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport

val DEFAULT_CONCURRENCY_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * [threads]개 스레드를 준비시킨 뒤 CountDownLatch 하나로 동시에 출발시키고, 각 [action]의 결과나 예외를 인덱스 순서대로 모은다.
 * [timeout] 안에 끝나지 않으면(데드락, 락 대기) AssertionError로 실패한다.
 */
fun <T> runConcurrently(
    threads: Int,
    timeout: Duration = DEFAULT_CONCURRENCY_TIMEOUT,
    action: (index: Int) -> T,
): ConcurrentOutcome<T> {
    require(threads > 0) { "threads must be positive" }
    val ready = CountDownLatch(threads)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(threads)
    try {
        val futures =
            (0 until threads).map { index ->
                executor.submit(
                    Callable {
                        ready.countDown()
                        start.await()
                        runCatching { action(index) }
                    },
                )
            }
        if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw AssertionError("스레드 $threads 개가 준비되지 않았다")
        start.countDown()
        val deadline = System.nanoTime() + timeout.toNanos()
        val results =
            futures.map { future ->
                try {
                    future.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                } catch (e: TimeoutException) {
                    throw AssertionError("동시 실행이 $timeout 안에 끝나지 않았다(데드락이나 락 대기를 의심한다)", e)
                }
            }
        return ConcurrentOutcome(results)
    } finally {
        executor.shutdownNow()
    }
}

/** 동시 실행 결과. [results]는 스레드 인덱스 순서다. */
class ConcurrentOutcome<T>(
    val results: List<Result<T>>,
) {
    val successes: List<T> get() = results.mapNotNull { it.getOrNull() }
    val failures: List<Throwable> get() = results.mapNotNull { it.exceptionOrNull() }

    /**
     * 실패가 모두 SnuttException이면 그 ErrorType 목록을 돌려준다.
     * 데드락, lock wait timeout, unique 제약 위반(500의 원인)처럼 정해지지 않은 예외가 하나라도 있으면 그 예외를 원인으로 실패한다.
     */
    fun errorTypes(): List<ErrorType> =
        failures.map { failure ->
            (failure as? SnuttException)?.error
                ?: throw AssertionError("정해지지 않은 예외로 실패한 요청이 있다: $failure", failure)
        }

    /** 성공 [successes]개, 나머지는 모두 [errorType]으로 실패했는지 검증한다. */
    fun assertOutcome(
        successes: Int,
        errorType: ErrorType? = null,
    ) {
        val types = errorTypes()
        if (this.successes.size != successes || types.any { it != errorType }) {
            throw AssertionError("기대: 성공 $successes, 나머지 $errorType / 실제: 성공 ${this.successes.size}, 실패 $types")
        }
    }

    fun assertAllSucceeded() = assertOutcome(successes = results.size)
}

/**
 * [condition]이 참이 될 때까지 짧게 쉬며 다시 확인한다. 고정 시간 sleep 대신 쓴다.
 * [timeout]을 넘기면 [message]로 실패한다.
 */
fun pollUntil(
    timeout: Duration = Duration.ofSeconds(10),
    message: () -> String = { "조건이 $timeout 안에 참이 되지 않았다" },
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError(message())
        LockSupport.parkNanos(POLL_INTERVAL_NANOS)
    }
}

private val POLL_INTERVAL_NANOS = Duration.ofMillis(2).toNanos()
