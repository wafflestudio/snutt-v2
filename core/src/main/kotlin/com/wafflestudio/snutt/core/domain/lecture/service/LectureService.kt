package com.wafflestudio.snutt.core.domain.lecture.service

import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureSearchRepository
import org.springframework.stereotype.Service

@Service
class LectureService(
    private val lectureRepository: LectureRepository,
    private val lectureSearchRepository: LectureSearchRepository,
    private val lectureClassTimeRepository: LectureClassTimeRepository,
) {
    fun search(criteria: LectureSearchCriteria): List<Lecture> = lectureSearchRepository.search(criteria)

    fun getAllByIds(lectureIds: Collection<Long>): Map<Long, Lecture> =
        lectureRepository.findAllById(lectureIds.distinct()).associateBy { it.id!! }

    fun classTimesByLectureId(lectureIds: Collection<Long>): Map<Long, List<ClassPlaceAndTime>> =
        lectureClassTimeRepository
            .findAllByLectureIdInOrderById(lectureIds)
            .groupBy({ it.lectureId!! }, { it.toClassPlaceAndTime() })
}
