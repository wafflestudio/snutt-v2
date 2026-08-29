package com.wafflestudio.snutt.core.domain.pushpreference.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

enum class PushPreferenceType {
    NORMAL,
    LECTURE_UPDATE,
    VACANCY_NOTIFICATION,
    DIARY,
}

@Entity
@Table(name = "push_preference")
class PushPreference(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    val user: User,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: PushPreferenceType,
    var isEnabled: Boolean = true,
) : BaseEntity()
