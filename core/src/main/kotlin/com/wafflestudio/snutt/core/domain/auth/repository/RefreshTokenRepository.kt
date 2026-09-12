package com.wafflestudio.snutt.core.domain.auth.repository

import com.wafflestudio.snutt.core.domain.auth.model.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {
    @Query("SELECT t FROM RefreshToken t JOIN FETCH t.user WHERE t.tokenHash = :tokenHash")
    fun findWithUserByTokenHash(tokenHash: String): RefreshToken?

    /**
     * 제시된 토큰이 아직 유효할 때만 새 토큰으로 교체한다.
     * 갱신된 행 수가 1이면 회전 성공, 0이면 존재하지 않거나 이미 만료된 토큰이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE RefreshToken t SET t.tokenHash = :newTokenHash, t.expiresAt = :newExpiresAt " +
            "WHERE t.tokenHash = :presentedTokenHash AND t.expiresAt > :now",
    )
    fun rotate(
        presentedTokenHash: String,
        newTokenHash: String,
        newExpiresAt: Instant,
        now: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.user.id = :userId")
    fun deleteAllByUserId(userId: Long): Int
}
