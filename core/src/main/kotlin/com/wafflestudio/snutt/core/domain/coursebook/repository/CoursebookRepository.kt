package com.wafflestudio.snutt.core.domain.coursebook.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface CoursebookRepository : JpaRepository<Coursebook, Long> {
    // v1 클라이언트가 /course_books의 updated_at으로 데이터 갱신을 감지하므로 sync마다 갱신한다
    @Modifying
    @Query("UPDATE Coursebook c SET c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :id")
    fun touchUpdatedAt(id: Long)

    fun findFirstByOrderByYearDescSemesterDesc(): Coursebook?

    fun findAllByOrderByYearDescSemesterDesc(): List<Coursebook>

    fun existsByYearAndSemester(
        year: Int,
        semester: Semester,
    ): Boolean
}
