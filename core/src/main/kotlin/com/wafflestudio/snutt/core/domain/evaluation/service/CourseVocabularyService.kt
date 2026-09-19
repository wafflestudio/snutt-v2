package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookVersionedCache
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseVocabulary
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseVocabularyRepository
import org.springframework.stereotype.Service

@Service
class CourseVocabularyService(
    private val courseVocabularyRepository: CourseVocabularyRepository,
    private val cache: CoursebookVersionedCache,
) {
    fun getVocabulary(language: Language): CourseVocabulary =
        cache.getOrPut(PREFIX, language.toString(), CourseVocabulary::class.java) {
            courseVocabularyRepository.getVocabulary(language)
        }

    companion object {
        private const val PREFIX = "course-vocabulary"
    }
}
