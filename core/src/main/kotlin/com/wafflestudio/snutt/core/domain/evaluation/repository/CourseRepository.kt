package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface CourseRepository : JpaRepository<Course, Long> {
    fun findByCourseNumberAndInstructor(
        courseNumber: String,
        instructor: String,
    ): Course?

    fun findAllByCourseNumberIn(courseNumbers: Collection<String>): List<Course>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Long): Course?

    @Modifying(flushAutomatically = true)
    @Query(
        value = """
        UPDATE course c
        LEFT JOIN (
            SELECT course_id, id
            FROM (
                SELECT id,
                       course_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY course_id
                           ORDER BY year DESC, semester DESC, updated_at DESC, id DESC
                       ) AS rn
                FROM lecture
                WHERE course_id IN (:courseIds)
            ) ranked
            WHERE rn = 1
        ) latest ON latest.course_id = c.id
        SET c.latest_lecture_id = latest.id
        WHERE c.id IN (:courseIds)
        """,
        nativeQuery = true,
    )
    fun refreshLatestLectures(courseIds: Collection<Long>): Int
}
