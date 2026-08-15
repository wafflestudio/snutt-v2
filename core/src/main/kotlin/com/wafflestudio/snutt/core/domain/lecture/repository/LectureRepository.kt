package com.wafflestudio.snutt.core.domain.lecture.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import org.springframework.data.jpa.repository.JpaRepository

interface LectureRepository : JpaRepository<Lecture, Long> {
    fun findByCourseIdOrderByYearDescSemesterDesc(courseId: Long): List<Lecture>

    fun findByExternalId(externalId: String): Lecture?

    fun findAllByExternalIdIn(externalIds: Collection<String>): List<Lecture>

    fun findByYearAndSemester(
        year: Int,
        semester: Semester,
    ): List<Lecture>

    // 수강스누 sync upsert 키
    fun findByYearAndSemesterAndCourseNumberAndLectureNumber(
        year: Int,
        semester: Semester,
        courseNumber: String,
        lectureNumber: String,
    ): Lecture?
}
