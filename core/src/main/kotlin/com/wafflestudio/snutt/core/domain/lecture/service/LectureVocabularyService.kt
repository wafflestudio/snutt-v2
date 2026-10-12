package com.wafflestudio.snutt.core.domain.lecture.service

import com.wafflestudio.snutt.core.common.cache.RedisCache
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureVocabulary
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class LectureVocabularyService(
    private val lectureRepository: LectureRepository,
    private val redisCache: RedisCache,
) {
    fun getVocabulary(
        year: Int,
        semester: Semester,
        language: Language,
    ): LectureVocabulary =
        redisCache.getOrPut(key(year, semester, language), TTL, LectureVocabulary::class.java) {
            lectureRepository.findVocabulary(year, semester, language)
        }

    fun evict(
        year: Int,
        semester: Semester,
    ) {
        redisCache.evict(*Language.entries.map { key(year, semester, it) }.toTypedArray())
    }

    private fun key(
        year: Int,
        semester: Semester,
        language: Language,
    ): String = "$PREFIX:$year-${semester.value}:$language"

    companion object {
        private const val PREFIX = "lecture-vocabulary"
        private val TTL: Duration = Duration.ofDays(1)
    }
}
