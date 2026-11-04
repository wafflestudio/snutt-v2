package com.wafflestudio.snutt.core.domain.diary.service

import org.springframework.data.redis.core.DefaultTypedTuple
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class DiaryPush(
    val userId: Long,
    val lectureId: Long,
    val sendAt: Instant,
)

@Component
class DiaryPushQueue(
    private val redisTemplate: StringRedisTemplate,
) {
    fun schedule(
        date: LocalDate,
        pushes: List<DiaryPush>,
    ): Boolean {
        if (redisTemplate.opsForValue().setIfAbsent("$KEY_PREFIX$date:planned", "1", KEY_TTL) != true) return false
        if (pushes.isEmpty()) return true
        val key = keyOf(date)
        redisTemplate
            .opsForZSet()
            .add(key, pushes.map { DefaultTypedTuple("${it.userId}:${it.lectureId}", it.sendAt.epochSecond.toDouble()) }.toSet())
        redisTemplate.expire(key, KEY_TTL)
        return true
    }

    fun popDue(
        date: LocalDate,
        now: Instant,
    ): List<Pair<Long, Long>> {
        val key = keyOf(date)
        val due = redisTemplate.opsForZSet().rangeByScore(key, 0.0, now.epochSecond.toDouble()).orEmpty()
        if (due.isEmpty()) return emptyList()
        redisTemplate.opsForZSet().remove(key, *due.toTypedArray())
        return due.map { it.substringBefore(':').toLong() to it.substringAfter(':').toLong() }
    }

    private fun keyOf(date: LocalDate) = "$KEY_PREFIX$date"

    private companion object {
        const val KEY_PREFIX = "diary-push:"
        val KEY_TTL: Duration = Duration.ofDays(1)
    }
}
