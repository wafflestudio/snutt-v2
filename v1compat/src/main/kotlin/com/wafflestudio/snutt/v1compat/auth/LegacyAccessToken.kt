package com.wafflestudio.snutt.v1compat.auth

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

@Entity
@Table(name = "legacy_access_token")
class LegacyAccessToken(
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "token_hash", nullable = false)
    val tokenHash: String,
) : BaseEntity()

interface LegacyAccessTokenRepository : JpaRepository<LegacyAccessToken, Long> {
    fun findByTokenHash(tokenHash: String): LegacyAccessToken?

    @Modifying
    @Query("DELETE FROM LegacyAccessToken t WHERE t.userId = :userId")
    fun deleteAllByUserId(userId: Long): Int
}
