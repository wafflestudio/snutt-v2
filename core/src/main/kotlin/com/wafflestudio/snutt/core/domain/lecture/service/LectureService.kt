package com.wafflestudio.snutt.core.domain.lecture.service

import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureSearchRepository
import org.springframework.stereotype.Service

@Service
class LectureService(
    private val lectureRepository: LectureRepository,
    private val lectureSearchRepository: LectureSearchRepository,
) {
    fun search(criteria: LectureSearchCriteria): List<Lecture> = lectureSearchRepository.search(criteria)

    fun getByExternalIdOrNull(externalId: String): Lecture? = lectureRepository.findByExternalId(externalId)
}
