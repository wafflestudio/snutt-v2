package com.wafflestudio.snutt.core.domain.coursebook.service

import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

@Component
class CoursebookVersionedCache(
    private val coursebookRepository: CoursebookRepository,
    private val redisTemplate: StringRedisTemplate,
    private val jsonMapper: JsonMapper,
) {
    fun <T : Any> getOrPut(
        prefix: String,
        scope: String,
        type: Class<T>,
        supplier: () -> T,
    ): T {
        val key = "$prefix:${version()}:$scope"
        redisTemplate.opsForValue().get(key)?.let { cached ->
            try {
                return jsonMapper.readValue(cached, type)
            } catch (_: JacksonException) {
                redisTemplate.delete(key)
            }
        }
        return supplier().also {
            redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(it), TTL)
        }
    }

    private fun version(): Instant = coursebookRepository.findFirstByOrderByUpdatedAtDesc()?.updatedAt ?: Instant.EPOCH

    companion object {
        private val TTL: Duration = Duration.ofDays(1)
    }
}
