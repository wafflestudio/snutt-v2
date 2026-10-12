package com.wafflestudio.snutt.core.common.cache

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

@Component
class RedisCache(
    private val redisTemplate: StringRedisTemplate,
    private val jsonMapper: JsonMapper,
) {
    fun <T : Any> getOrPut(
        key: String,
        ttl: Duration,
        type: Class<T>,
        supplier: () -> T,
    ): T {
        redisTemplate.opsForValue().get(key)?.let { cached ->
            try {
                return jsonMapper.readValue(cached, type)
            } catch (_: JacksonException) {
                redisTemplate.delete(key)
            }
        }
        return supplier().also {
            redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(it), ttl)
        }
    }

    fun evict(vararg keys: String) {
        redisTemplate.delete(keys.asList())
    }
}
