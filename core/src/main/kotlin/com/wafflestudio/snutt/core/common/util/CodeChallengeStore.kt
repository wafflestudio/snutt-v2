package com.wafflestudio.snutt.core.common.util

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

class CodeChallengeStore(
    private val redisTemplate: StringRedisTemplate,
    namespace: String,
    private val ttl: Duration = Duration.ofMinutes(3),
) {
    private val codePrefix = "$namespace:code:"
    private val attemptPrefix = "$namespace:attempt:"

    private data class Stored(
        val payload: String,
        val code: String,
        val sendCount: Int,
    )

    companion object {
        private const val MAX_SENDS = 5
        private val jsonMapper = JsonMapper.builder().findAndAddModules().build()
    }

    fun store(
        key: Any,
        code: String,
        payload: String = "",
    ) {
        val existing = read(key)
        if (existing != null && existing.sendCount >= MAX_SENDS) {
            throw SnuttException(ErrorType.TOO_MANY_VERIFICATION_CODE_REQUEST)
        }
        val stored = Stored(payload = payload, code = code, sendCount = (existing?.sendCount ?: 0) + 1)
        redisTemplate.opsForValue().set(codePrefix + key, jsonMapper.writeValueAsString(stored), ttl)
        redisTemplate.delete(attemptPrefix + key)
    }

    fun verify(
        key: Any,
        code: String,
    ): String {
        val attemptKey = attemptPrefix + key
        val attempts = redisTemplate.opsForValue().increment(attemptKey) ?: 1L
        redisTemplate.expire(attemptKey, ttl)
        if (attempts > VerificationCode.MAX_ATTEMPTS) throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        val stored = read(key) ?: throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        if (stored.code != code) throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        return stored.payload
    }

    fun clear(key: Any) {
        redisTemplate.delete(codePrefix + key)
        redisTemplate.delete(attemptPrefix + key)
    }

    private fun read(key: Any): Stored? =
        redisTemplate
            .opsForValue()
            .get(codePrefix + key)
            ?.let { runCatching { jsonMapper.readValue(it, Stored::class.java) }.getOrNull() }
}
