package com.wafflestudio.snutt.core.domain.lecture.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.common.pagination.toCursorPage
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCursor
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureSearchRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureSearchRow
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

@Service
class LectureService(
    private val lectureRepository: LectureRepository,
    private val lectureSearchRepository: LectureSearchRepository,
    private val lectureClassTimeRepository: LectureClassTimeRepository,
) {
    fun search(
        criteria: LectureSearchCriteria,
        cursor: String?,
        limit: Int = 20,
    ): CursorPage<LectureSearchRow> {
        if (limit <= 0) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val decoded =
            CursorCodec.decode<LectureSearchCursor>(cursor)?.also {
                if (it.sort != criteria.sort || it.lectureId <= 0) {
                    throw SnuttException(ErrorType.INVALID_CURSOR)
                }
            }
        val results = lectureSearchRepository.search(criteria, decoded?.lectureId, limit + 1)
        return results.toCursorPage(limit, cursorOf = { LectureSearchCursor(criteria.sort, it.lecture.id!!) }) { it }
    }

    fun get(lectureId: Long): Lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)

    fun getAllByIds(lectureIds: Collection<Long>): Map<Long, Lecture> =
        lectureRepository.findAllById(lectureIds.distinct()).associateBy { it.id!! }

    fun classTimesByLectureId(lectureIds: Collection<Long>): Map<Long, List<ClassPlaceAndTime>> =
        lectureClassTimeRepository
            .findAllByLectureIdInOrderById(lectureIds)
            .groupBy({ it.lectureId!! }, { it.toClassPlaceAndTime() })
}
