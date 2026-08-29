package com.wafflestudio.snutt.core.domain.diary.repository

import com.wafflestudio.snutt.core.domain.diary.model.DiarySubmission
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface DiarySubmissionRepository : JpaRepository<DiarySubmission, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<DiarySubmission>

    fun findByUserIdAndCreatedAtAfter(
        userId: Long,
        createdAt: Instant,
    ): List<DiarySubmission>
}
