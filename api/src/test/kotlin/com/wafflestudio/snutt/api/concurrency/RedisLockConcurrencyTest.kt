package com.wafflestudio.snutt.api.concurrency

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.util.CodeChallengeStore
import com.wafflestudio.snutt.core.common.util.SchedulerLock
import com.wafflestudio.snutt.core.common.util.VerificationCode
import com.wafflestudio.snutt.core.domain.user.service.EmailVerificationService
import com.wafflestudio.snutt.core.test.concurrency.pollUntil
import com.wafflestudio.snutt.core.test.concurrency.runConcurrently
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Redis setIfAbsent·INCR로 만든 보호 장치(SchedulerLock, 인증코드 발송·검증 제한)의 race. */
class RedisLockConcurrencyTest : AbstractConcurrencyTest() {
    @Autowired
    lateinit var schedulerLock: SchedulerLock

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var redisJsonMapper: JsonMapper

    @Autowired
    lateinit var emailVerificationService: EmailVerificationService

    @Test
    fun `두 인스턴스가 같은 스케줄러 락을 동시에 잡으면 한쪽만 실행된다`() {
        val instances = 4
        val executed = AtomicInteger()
        val skipped = AtomicInteger()
        val outcome =
            runConcurrently(instances) {
                var ran = false
                schedulerLock.withLock("race", Duration.ofSeconds(30)) {
                    ran = true
                    executed.incrementAndGet()
                    // 실행 중인 동안 나머지 인스턴스가 모두 시도하고 돌아올 때까지 락을 쥐고 있는다.
                    pollUntil { skipped.get() == instances - 1 }
                }
                if (!ran) skipped.incrementAndGet()
            }
        outcome.assertAllSucceeded()
        assertEquals(1, executed.get())
        assertFalse(redisTemplate.hasKey(LOCK_KEY_RACE))
    }

    @Test
    fun `TTL이 지나면 다른 인스턴스가 락을 다시 잡고, 늦게 끝난 인스턴스는 남의 락을 풀지 못한다`() {
        val firstHolding = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val first =
            CompletableFuture.runAsync {
                schedulerLock.withLock("ttl", Duration.ofMillis(300)) {
                    firstHolding.countDown()
                    firstRelease.await(30, TimeUnit.SECONDS)
                }
            }
        assertTrue(firstHolding.await(10, TimeUnit.SECONDS))

        var ranWhileHeld = false
        schedulerLock.withLock("ttl", Duration.ofSeconds(30)) { ranWhileHeld = true }
        assertFalse(ranWhileHeld)

        pollUntil { !redisTemplate.hasKey(LOCK_KEY_TTL) }
        var secondRan = false
        schedulerLock.withLock("ttl", Duration.ofSeconds(30)) {
            secondRan = true
            val secondToken = redisTemplate.opsForValue().get(LOCK_KEY_TTL)
            firstRelease.countDown()
            first.get(10, TimeUnit.SECONDS)
            assertEquals(secondToken, redisTemplate.opsForValue().get(LOCK_KEY_TTL))
        }
        assertTrue(secondRan)
        assertFalse(redisTemplate.hasKey(LOCK_KEY_TTL))
    }

    @Test
    fun `인증코드 발송을 동시에 요청하면 메일은 한 번만 나가고 나머지는 발송 제한이다`() {
        val user = fixtures.user("sender", email = null)
        val outcome = runConcurrently(REQUESTS) { emailVerificationService.sendVerificationCode(user.id!!, "sender@snu.ac.kr") }
        outcome.assertOutcome(successes = 1, ErrorType.TOO_MANY_VERIFICATION_CODE_REQUESTS)
        verify(mailClient, times(1)).send(eq("sender@snu.ac.kr"), any(), any())
    }

    @Test
    fun `틀린 인증코드를 동시에 많이 넣어도 시도 횟수 제한을 넘지 못한다`() {
        val store = CodeChallengeStore(redisTemplate, redisJsonMapper, "concurrency-test")
        store.store(1L, "123456")
        val guesses = VerificationCode.MAX_ATTEMPTS * 3
        val outcome = runConcurrently(guesses) { store.verify(1L, "wrong$it") }
        outcome.assertOutcome(successes = 0, ErrorType.INVALID_VERIFICATION_CODE)
        assertEquals(guesses.toString(), redisTemplate.opsForValue().get("concurrency-test:attempt:1"))
        runConcurrently(1) { store.verify(1L, "123456") }.assertOutcome(successes = 0, ErrorType.INVALID_VERIFICATION_CODE)
    }

    private companion object {
        const val LOCK_KEY_RACE = "scheduler-lock:race"
        const val LOCK_KEY_TTL = "scheduler-lock:ttl"
    }
}
