package com.wafflestudio.snutt.core.domain.user.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

@Entity
class UserSocialAuth(
    var userId: Long,
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var provider: AuthProvider,
    var sub: String,
    var email: String? = null,
    var displayName: String? = null,
    var transferSub: String? = null,
) : BaseEntity()
