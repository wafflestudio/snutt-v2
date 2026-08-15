package com.wafflestudio.snutt.core.domain.lecture.service

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureVocabulary
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureVocabularyRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

@Service
class LectureVocabularyService(
    private val lectureVocabularyRepository: LectureVocabularyRepository,
    private val redisTemplate: StringRedisTemplate,
) {
    fun getVocabulary(
        year: Int?,
        semester: Semester?,
        language: Language,
    ): LectureVocabulary {
        val key = cacheKey(year, semester, language)
        redisTemplate.opsForValue().get(key)?.let { cached ->
            runCatching { jsonMapper.readValue(cached, LectureVocabulary::class.java) }.getOrNull()?.let { return it }
        }
        val vocabulary = lectureVocabularyRepository.findVocabulary(year, semester, language)
        redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(vocabulary), TTL)
        return vocabulary
    }

    fun invalidate() {
        redisTemplate.opsForValue().increment(VERSION_KEY)
    }

    private fun cacheKey(
        year: Int?,
        semester: Semester?,
        language: Language,
    ): String {
        val version = redisTemplate.opsForValue().get(VERSION_KEY) ?: "0"
        val scope = if (year != null && semester != null) "$year-${semester.value}" else "all"
        return "$PREFIX:$version:$scope:$language"
    }

    companion object {
        private const val PREFIX = "lecture-vocabulary"
        private const val VERSION_KEY = "$PREFIX:version"

        private val TTL: Duration = Duration.ofDays(1)
        private val jsonMapper: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    }
}
