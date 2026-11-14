package com.wafflestudio.snutt.core.domain.vacancy.repository

import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import com.wafflestudio.snutt.core.domain.vacancy.service.VacancyLectureDisplay
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface VacancyNotificationRepository : JpaRepository<VacancyNotification, Long> {
    @Query(
        "SELECT new com.wafflestudio.snutt.core.domain.vacancy.service.VacancyLectureDisplay(l, s) " +
            "FROM VacancyNotification v " +
            "JOIN Lecture l ON l.id = v.lectureId " +
            "LEFT JOIN LectureRegistrationStatus s ON s.lectureId = l.id " +
            "WHERE v.userId = :userId",
    )
    fun findLectureDisplaysByUserId(userId: Long): List<VacancyLectureDisplay>

    fun findByLectureIdIn(lectureIds: Collection<Long>): List<VacancyNotification>

    fun existsByUserIdAndLectureId(
        userId: Long,
        lectureId: Long,
    ): Boolean

    fun deleteByUserIdAndLectureId(
        userId: Long,
        lectureId: Long,
    )
}
