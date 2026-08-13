package com.wafflestudio.snutt.core.domain.lecture.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
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

    fun getByExternalIdOrNull(externalId: String): Lecture? = lectureRepository.findByExternalId(externalId)

    fun getByExternalId(externalId: String): Lecture =
        lectureRepository.findByExternalId(externalId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)

    fun getAllByIds(lectureIds: Collection<Long>): Map<Long, Lecture> =
        lectureRepository.findAllById(lectureIds.distinct()).associateBy { it.id!! }

    fun getIdsByExternalIds(externalIds: Collection<String>): Map<String, Long> =
        if (externalIds.isEmpty()) {
            emptyMap()
        } else {
            lectureRepository.findAllByExternalIdIn(externalIds.distinct()).associate { it.externalId to it.id!! }
        }

    // 강의 시간은 lecture_class_time 테이블이 단일 원천이다. 읽기 경로가 1회 배치로 파생한다 (N+1 없음)
    fun classTimesByLectureId(lectureIds: Collection<Long>): Map<Long, List<ClassPlaceAndTime>> =
        lectureClassTimeRepository
            .findAllByLectureIdInOrderById(lectureIds)
            .groupBy({ it.lectureId!! }, { it.toClassPlaceAndTime() })
}
