package com.wafflestudio.snutt.core.domain.pushpreference.repository

import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreference
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface PushPreferenceRepository : JpaRepository<PushPreference, Long> {
    fun findAllByUserId(userId: Long): List<PushPreference>

    @Query(
        "SELECT p.user.id FROM PushPreference p " +
            "WHERE p.user.id IN :userIds AND p.type = :type AND p.isEnabled = false",
    )
    fun findDisabledUserIds(
        userIds: Collection<Long>,
        type: PushPreferenceType,
    ): List<Long>
}
