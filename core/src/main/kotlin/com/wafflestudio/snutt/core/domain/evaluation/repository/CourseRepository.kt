package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface CourseRepository : JpaRepository<Course, Long> {
    fun findByCourseNumberAndInstructor(
        courseNumber: String,
        instructor: String,
    ): Course?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Course c WHERE c.id = :id")
    fun findByIdForUpdate(id: Long): Course?
}
