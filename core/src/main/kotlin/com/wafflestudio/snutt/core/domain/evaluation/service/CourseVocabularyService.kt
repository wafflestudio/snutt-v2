package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.cache.RedisCache
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseVocabulary
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class CourseVocabularyService(
    private val courseRepository: CourseRepository,
    private val redisCache: RedisCache,
) {
    fun getVocabulary(language: Language): CourseVocabulary =
        redisCache.getOrPut(key(language), TTL, CourseVocabulary::class.java) {
            courseRepository.getVocabulary(language)
        }

    fun evict() {
        redisCache.evict(*Language.entries.map(::key).toTypedArray())
    }

    private fun key(language: Language): String = "$PREFIX:$language"

    companion object {
        private const val PREFIX = "course-vocabulary"
        private val TTL: Duration = Duration.ofDays(1)
    }
}
