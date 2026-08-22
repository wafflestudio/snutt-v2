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
    private val maxSendsPerHour: Int = 5,
) {
    private val codePrefix = "$namespace:code:"
    private val attemptPrefix = "$namespace:attempt:"
    private val sendMinutePrefix = "$namespace:send-minute:"
    private val sendHourPrefix = "$namespace:send-hour:"

    private data class Stored(
        val payload: String,
        val code: String,
    )

    companion object {
        private val jsonMapper: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    }

    fun store(
        key: Any,
        code: String,
        payload: String = "",
    ) {
        // 발송 제한은 코드 TTL과 별개 카운터: 1분에 1회, 1시간(고정 창)에 maxSendsPerHour회
        val firstInMinute =
            redisTemplate.opsForValue().setIfAbsent(sendMinutePrefix + key, "1", Duration.ofMinutes(1)) ?: false
        if (!firstInMinute) throw SnuttException(ErrorType.TOO_MANY_VERIFICATION_CODE_REQUEST)

        val hourKey = sendHourPrefix + key
        val sendsThisHour = redisTemplate.opsForValue().increment(hourKey) ?: 1L
        if (sendsThisHour == 1L) {
            redisTemplate.expire(hourKey, Duration.ofHours(1))
        }
        if (sendsThisHour > maxSendsPerHour) throw SnuttException(ErrorType.TOO_MANY_VERIFICATION_CODE_REQUEST)

        val stored = Stored(payload = payload, code = code)
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
