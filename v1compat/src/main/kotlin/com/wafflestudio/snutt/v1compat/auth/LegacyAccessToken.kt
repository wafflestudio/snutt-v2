package com.wafflestudio.snutt.v1compat.auth

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

@Entity
class LegacyAccessToken(
    val userId: Long,
    val tokenHash: String,
) : BaseEntity()

interface LegacyAccessTokenRepository : JpaRepository<LegacyAccessToken, Long> {
    fun findByTokenHash(tokenHash: String): LegacyAccessToken?

    @Modifying
    @Query("DELETE FROM LegacyAccessToken t WHERE t.userId = :userId")
    fun deleteAllByUserId(userId: Long): Int
}
