package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.domain.evaluation.model.CourseSemester
import org.springframework.data.jpa.repository.JpaRepository

interface CourseSemesterRepository : JpaRepository<CourseSemester, Long> {
    fun findByCourseIdOrderByYearDescSemesterDesc(courseId: Long): List<CourseSemester>

    fun findByCourseIdIn(courseIds: Collection<Long>): List<CourseSemester>
}
