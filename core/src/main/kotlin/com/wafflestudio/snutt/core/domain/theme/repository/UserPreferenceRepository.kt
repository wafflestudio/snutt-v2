package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.UserPreference
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface UserPreferenceRepository : JpaRepository<UserPreference, Long> {
    fun findByUserId(userId: Long): UserPreference?

    @Modifying
    @Query(
        value =
            "INSERT INTO user_preference (user_id, default_theme_id) VALUES (:userId, :themeId) " +
                "ON DUPLICATE KEY UPDATE default_theme_id = :themeId",
        nativeQuery = true,
    )
    fun upsert(
        userId: Long,
        themeId: Long,
    )
}
