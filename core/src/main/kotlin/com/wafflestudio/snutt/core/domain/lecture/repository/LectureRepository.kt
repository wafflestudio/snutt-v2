package com.wafflestudio.snutt.core.domain.lecture.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import org.springframework.data.jpa.repository.JpaRepository

interface LectureRepository : JpaRepository<Lecture, Long> {
    fun findByExternalId(externalId: String): Lecture?

    fun findByYearAndSemester(
        year: Int,
        semester: Semester,
    ): List<Lecture>

    // 수강스누 sync upsert 키 (PLAN.md §2 uk_lecture_semester_course_lecture_number)
    fun findByYearAndSemesterAndCourseNumberAndLectureNumber(
        year: Int,
        semester: Semester,
        courseNumber: String,
        lectureNumber: String,
    ): Lecture?
}
