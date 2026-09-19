package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface CourseRepository : JpaRepository<Course, Long> {
    fun findByCourseNumberAndInstructor(
        courseNumber: String,
        instructor: String,
    ): Course?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Long): Course?
}
