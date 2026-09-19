package com.wafflestudio.snutt.core.domain.auth.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ManyToOne
import java.time.Instant

@Entity
class RefreshToken(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    val user: User,
    @Column(nullable = false, columnDefinition = "char(64)")
    var tokenHash: String,
    var expiresAt: Instant,
) : BaseEntity()
