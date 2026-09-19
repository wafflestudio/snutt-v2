package com.wafflestudio.snutt.core.domain.lecture.service

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.json.Json
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureVocabulary
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureVocabularyRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import java.time.Duration
import java.time.Instant

@Service
class LectureVocabularyService(
    private val lectureVocabularyRepository: LectureVocabularyRepository,
    private val coursebookRepository: CoursebookRepository,
    private val redisTemplate: StringRedisTemplate,
) {
    fun getVocabulary(
        year: Int?,
        semester: Semester?,
        language: Language,
    ): LectureVocabulary {
        val key = cacheKey(year, semester, language)
        redisTemplate.opsForValue().get(key)?.let { cached ->
            try {
                return Json.mapper.readValue(cached, LectureVocabulary::class.java)
            } catch (e: JacksonException) {
                redisTemplate.delete(key)
            }
        }
        val vocabulary = lectureVocabularyRepository.findVocabulary(year, semester, language)
        redisTemplate.opsForValue().set(key, Json.mapper.writeValueAsString(vocabulary), TTL)
        return vocabulary
    }

    private fun cacheKey(
        year: Int?,
        semester: Semester?,
        language: Language,
    ): String {
        val version = coursebookRepository.findFirstByOrderByUpdatedAtDesc()?.updatedAt ?: Instant.EPOCH
        val scope = if (year != null && semester != null) "$year-${semester.value}" else "all"
        return "$PREFIX:$version:$scope:$language"
    }

    companion object {
        private const val PREFIX = "lecture-vocabulary"
        private val TTL: Duration = Duration.ofDays(1)
    }
}
