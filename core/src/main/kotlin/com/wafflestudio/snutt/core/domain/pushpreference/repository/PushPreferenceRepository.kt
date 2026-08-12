package com.wafflestudio.snutt.core.domain.pushpreference.repository

import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreference
import org.springframework.data.jpa.repository.JpaRepository

interface PushPreferenceRepository : JpaRepository<PushPreference, Long> {
    fun findAllByUserId(userId: Long): List<PushPreference>
}
