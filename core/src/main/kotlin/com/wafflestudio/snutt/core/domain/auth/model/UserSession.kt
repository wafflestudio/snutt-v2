package com.wafflestudio.snutt.core.domain.auth.model

import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import com.wafflestudio.snutt.core.domain.device.model.UserDevice
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "user_session")
class UserSession(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    val user: User,
    // refresh token 원문의 SHA-256 hex. 원문은 저장하지 않는다
    @Column(nullable = false, columnDefinition = "char(64)")
    var refreshTokenHash: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_device_id")
    var userDevice: UserDevice? = null,
    var expiresAt: Instant,
    var revokedAt: Instant? = null,
    var lastUsedAt: Instant = Instant.now(),
) : ExternalIdEntity() {
    val isValid: Boolean
        get() = revokedAt == null && expiresAt.isAfter(Instant.now())
}
