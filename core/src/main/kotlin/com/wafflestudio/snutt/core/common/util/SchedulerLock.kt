package com.wafflestudio.snutt.core.common.util

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class SchedulerLock(
    private val redisTemplate: StringRedisTemplate,
) {
    fun withLock(
        key: String,
        ttl: Duration,
        block: () -> Unit,
    ) {
        val token = UUID.randomUUID().toString()
        val lockKey = "scheduler-lock:$key"
        if (redisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl) != true) return
        try {
            block()
        } finally {
            // 실행이 TTL을 넘겨 타 인스턴스가 잠금을 선점한 경우 그 잠금을 지우지 않도록 자신의 것만 해제한다
            redisTemplate.execute(UNLOCK_SCRIPT, listOf(lockKey), token)
        }
    }

    companion object {
        private val UNLOCK_SCRIPT =
            DefaultRedisScript(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                Long::class.java,
            )
    }
}
