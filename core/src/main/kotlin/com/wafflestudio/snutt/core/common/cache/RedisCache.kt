package com.wafflestudio.snutt.core.common.cache

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
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
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T : Any> getOrPut(
        key: String,
        ttl: Duration,
        type: Class<T>,
        supplier: () -> T,
    ): T {
        read(key, type)?.let { return it }
        val value = supplier()
        tolerating("set $key") { redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(value), ttl) }
        return value
    }

    fun evict(vararg keys: String) {
        tolerating("evict ${keys.joinToString()}") { redisTemplate.delete(keys.asList()) }
    }

    private fun <T : Any> read(
        key: String,
        type: Class<T>,
    ): T? {
        val cached = tolerating("get $key") { redisTemplate.opsForValue().get(key) } ?: return null
        return try {
            jsonMapper.readValue(cached, type)
        } catch (_: JacksonException) {
            evict(key)
            null
        }
    }

    private fun <R> tolerating(
        operation: String,
        block: () -> R,
    ): R? =
        try {
            block()
        } catch (e: DataAccessException) {
            log.warn("redis cache {} failed, continuing without cache", operation, e)
            null
        }
}
