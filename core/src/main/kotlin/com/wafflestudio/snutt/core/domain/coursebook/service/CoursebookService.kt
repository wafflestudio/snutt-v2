package com.wafflestudio.snutt.core.domain.coursebook.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import org.springframework.stereotype.Service

@Service
class CoursebookService(
    private val coursebookRepository: CoursebookRepository,
) {
    fun getLatestCoursebook(): Coursebook = findLatestCoursebook() ?: throw SnuttException(ErrorType.DEFAULT_ERROR)

    // 수강편람이 하나도 없는 상태를 정상 흐름으로 다루는 호출자용만 쓴다 (초기 배포, 빈 DB)
    fun findLatestCoursebook(): Coursebook? = coursebookRepository.findFirstByOrderByYearDescSemesterDesc()

    fun getCoursebooks(): List<Coursebook> = coursebookRepository.findAllByOrderByYearDescSemesterDesc()

    fun existsCoursebook(
        year: Int,
        semester: Semester,
    ): Boolean = coursebookRepository.existsByYearAndSemester(year, semester)
}
