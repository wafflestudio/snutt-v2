package com.wafflestudio.snutt.core.domain.auth.repository

import com.wafflestudio.snutt.core.domain.auth.model.UserSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface UserSessionRepository : JpaRepository<UserSession, Long> {
    fun findByRefreshTokenHash(refreshTokenHash: String): UserSession?

    fun findByExternalId(externalId: String): UserSession?

    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :now WHERE s.user.id = :userId AND s.revokedAt IS NULL")
    fun revokeAllByUserId(
        userId: Long,
        now: Instant = Instant.now(),
    ): Int
}
