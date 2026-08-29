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

    // 집계 갱신은 같은 course에 대한 동시 쓰기를 직렬화해야 값이 유실되지 않는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Course c WHERE c.id = :id")
    fun findByIdForUpdate(id: Long): Course?
}
