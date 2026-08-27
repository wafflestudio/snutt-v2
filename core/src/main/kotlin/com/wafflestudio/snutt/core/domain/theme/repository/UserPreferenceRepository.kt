package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.UserPreference
import org.springframework.data.jpa.repository.JpaRepository

interface UserPreferenceRepository : JpaRepository<UserPreference, Long> {
    fun findByUserId(userId: Long): UserPreference?
}
