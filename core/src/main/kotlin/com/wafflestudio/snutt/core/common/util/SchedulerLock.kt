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
