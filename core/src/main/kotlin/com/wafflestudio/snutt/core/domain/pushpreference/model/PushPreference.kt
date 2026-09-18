package com.wafflestudio.snutt.core.domain.pushpreference.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ManyToOne

enum class PushPreferenceType {
    NORMAL,
    LECTURE_UPDATE,
    VACANCY_NOTIFICATION,
    DIARY,
}

@Entity
class PushPreference(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    val user: User,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: PushPreferenceType,
    var isEnabled: Boolean = true,
) : BaseEntity()
