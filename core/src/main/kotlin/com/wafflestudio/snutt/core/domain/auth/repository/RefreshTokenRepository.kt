package com.wafflestudio.snutt.core.domain.auth.repository

import com.wafflestudio.snutt.core.domain.auth.model.RefreshToken
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {
    @EntityGraph(attributePaths = ["user"])
    fun findByTokenHash(tokenHash: String): RefreshToken?

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
