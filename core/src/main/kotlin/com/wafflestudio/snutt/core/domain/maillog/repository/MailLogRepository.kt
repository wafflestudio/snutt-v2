package com.wafflestudio.snutt.core.domain.maillog.repository

import com.wafflestudio.snutt.core.domain.maillog.model.MailLog
import com.wafflestudio.snutt.core.domain.maillog.model.MailLogStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface MailLogRepository : JpaRepository<MailLog, Long> {
    @Modifying
    @Query("UPDATE MailLog m SET m.status = :status, m.errorMessage = :errorMessage, m.updatedAt = :now WHERE m.id = :id")
    fun complete(
        id: Long,
        status: MailLogStatus,
        errorMessage: String?,
        now: Instant,
    )

    fun findByEmailOrderByIdDesc(email: String): List<MailLog>

    fun findByRequestedLocalIdOrderByIdDesc(requestedLocalId: String): List<MailLog>

    @Modifying
    @Query("DELETE FROM MailLog m WHERE m.createdAt < :before")
    fun deleteCreatedBefore(before: Instant): Int
}
