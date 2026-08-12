package com.wafflestudio.snutt.core.domain.coursebook.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import org.springframework.data.jpa.repository.JpaRepository

interface CoursebookRepository : JpaRepository<Coursebook, Long> {
    fun findFirstByOrderByYearDescSemesterDesc(): Coursebook?

    fun findAllByOrderByYearDescSemesterDesc(): List<Coursebook>

    fun existsByYearAndSemester(
        year: Int,
        semester: Semester,
    ): Boolean
}
