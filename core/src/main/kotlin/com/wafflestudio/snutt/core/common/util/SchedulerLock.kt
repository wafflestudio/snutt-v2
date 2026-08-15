package com.wafflestudio.snutt.core.common.util

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

// 다중 인스턴스 중복 실행 방지용 Redis 락 (v1 cache.withLock 이식)
@Component
class SchedulerLock(
    private val redisTemplate: StringRedisTemplate,
) {
    fun withLock(
        key: String,
        ttl: Duration,
        block: () -> Unit,
    ) {
        if (redisTemplate.opsForValue().setIfAbsent("scheduler-lock:$key", "1", ttl) != true) return
        try {
            block()
        } finally {
            redisTemplate.delete("scheduler-lock:$key")
        }
    }
}
