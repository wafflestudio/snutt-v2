package com.wafflestudio.snutt.core.domain.auth.repository

import com.wafflestudio.snutt.core.domain.auth.model.UserSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface UserSessionRepository : JpaRepository<UserSession, Long> {
    @Query("SELECT s FROM UserSession s JOIN FETCH s.user WHERE s.id = :sessionId")
    fun findWithUserById(sessionId: Long): UserSession?

    @Query("SELECT s FROM UserSession s JOIN FETCH s.user WHERE s.refreshTokenHash = :refreshTokenHash")
    fun findWithUserByRefreshTokenHash(refreshTokenHash: String): UserSession?

    @Modifying
    @Query(
        "UPDATE UserSession s SET s.revokedAt = :now " +
            "WHERE s.refreshTokenHash = :refreshTokenHash AND s.revokedAt IS NULL AND s.expiresAt > :now",
    )
    fun revokeIfActive(
        refreshTokenHash: String,
        now: Instant,
    ): Int

    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :now WHERE s.user.id = :userId AND s.revokedAt IS NULL")
    fun revokeAllByUserId(
        userId: Long,
        now: Instant = Instant.now(),
    ): Int
}
