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
    fun getLatestCoursebook(): Coursebook =
        coursebookRepository.findFirstByOrderByYearDescSemesterDesc()
            ?: throw SnuttException(ErrorType.DEFAULT_ERROR) // v1은 빈 컬렉션에서 NPE 500이었다

    fun getCoursebooks(): List<Coursebook> = coursebookRepository.findAllByOrderByYearDescSemesterDesc()

    fun existsCoursebook(
        year: Int,
        semester: Semester,
    ): Boolean = coursebookRepository.existsByYearAndSemester(year, semester)
}
