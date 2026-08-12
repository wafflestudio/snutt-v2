package com.wafflestudio.snutt.core.domain.vacancy.repository

import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import org.springframework.data.jpa.repository.JpaRepository

interface VacancyNotificationRepository : JpaRepository<VacancyNotification, Long> {
    fun findByUserId(userId: Long): List<VacancyNotification>

    fun findByLectureId(lectureId: Long): List<VacancyNotification>

    fun existsByUserIdAndLectureId(
        userId: Long,
        lectureId: Long,
    ): Boolean

    fun deleteByUserIdAndLectureId(
        userId: Long,
        lectureId: Long,
    )
}
