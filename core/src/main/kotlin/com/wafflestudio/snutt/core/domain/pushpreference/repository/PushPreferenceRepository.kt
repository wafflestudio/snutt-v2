package com.wafflestudio.snutt.core.domain.pushpreference.repository

import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreference
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import org.springframework.data.jpa.repository.JpaRepository

interface PushPreferenceRepository : JpaRepository<PushPreference, Long> {
    fun findAllByUserId(userId: Long): List<PushPreference>

    fun findByUserIdInAndTypeAndIsEnabledFalse(
        userIds: Collection<Long>,
        type: PushPreferenceType,
    ): List<PushPreference>
}
