package com.wafflestudio.snutt.core.domain.lecture.service

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookVersionedCache
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureVocabulary
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureVocabularyRepository
import org.springframework.stereotype.Service

@Service
class LectureVocabularyService(
    private val lectureVocabularyRepository: LectureVocabularyRepository,
    private val cache: CoursebookVersionedCache,
) {
    fun getVocabulary(
        year: Int?,
        semester: Semester?,
        language: Language,
    ): LectureVocabulary {
        val scope = if (year != null && semester != null) "$year-${semester.value}" else "all"
        return cache.getOrPut(PREFIX, "$scope:$language", LectureVocabulary::class.java) {
            lectureVocabularyRepository.findVocabulary(year, semester, language)
        }
    }

    companion object {
        private const val PREFIX = "lecture-vocabulary"
    }
}
