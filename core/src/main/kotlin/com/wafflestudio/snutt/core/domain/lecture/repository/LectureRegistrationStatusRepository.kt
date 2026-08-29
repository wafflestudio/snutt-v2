package com.wafflestudio.snutt.core.domain.lecture.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface LectureRegistrationStatusRepository : JpaRepository<LectureRegistrationStatus, Long> {
    @Query(
        "SELECT s FROM LectureRegistrationStatus s WHERE s.lectureId IN " +
            "(SELECT l.id FROM Lecture l WHERE l.year = :year AND l.semester = :semester)",
    )
    fun findByYearAndSemester(
        year: Int,
        semester: Semester,
    ): List<LectureRegistrationStatus>
}
