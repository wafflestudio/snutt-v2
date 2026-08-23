package com.wafflestudio.snutt.core.domain.lecture.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import org.springframework.data.jpa.repository.JpaRepository

interface LectureRepository : JpaRepository<Lecture, Long> {
    fun findByCourseIdOrderByYearDescSemesterDesc(courseId: Long): List<Lecture>

    fun findByYearAndSemester(
        year: Int,
        semester: Semester,
    ): List<Lecture>
}
