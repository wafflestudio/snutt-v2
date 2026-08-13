package com.wafflestudio.snutt.core.domain.auth.repository

import com.wafflestudio.snutt.core.domain.auth.model.UserSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface UserSessionRepository : JpaRepository<UserSession, Long> {
    fun findByRefreshTokenHash(refreshTokenHash: String): UserSession?

    fun findByExternalId(externalId: String): UserSession?

    @Query("SELECT s FROM UserSession s JOIN FETCH s.user WHERE s.externalId = :externalId")
    fun findWithUserByExternalId(externalId: String): UserSession?

    @Query("SELECT s FROM UserSession s JOIN FETCH s.user WHERE s.refreshTokenHash = :refreshTokenHash")
    fun findWithUserByRefreshTokenHash(refreshTokenHash: String): UserSession?

    /**
     * 유효한 세션을 폐기 상태로 바꾸고 바뀐 행 수를 준다. 회전을 조건부 UPDATE 한 번으로 확정하므로
     * 같은 refresh token으로 동시에 들어온 요청 중 하나만 1을 받는다.
     */
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
