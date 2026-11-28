package com.wafflestudio.snutt.core.domain.user.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

data class Nickname(
    val name: String,
    val tag: String,
)

@Entity
@Table(name = "`user`")
class User(
    var email: String? = null,
    /** null은 v1에서 인증 여부 필드가 없던 이관 계정이다. 미인증으로 보되, 구앱 강의평 접근은 v1처럼 막지 않는다. */
    var isEmailVerified: Boolean? = false,
    var nickname: String,
    @Column(nullable = false, length = 4)
    var nicknameTag: String,
    var localId: String? = null,
    var localPw: String? = null,
    var active: Boolean = true,
    var isAdmin: Boolean = false,
    var tokenVersion: Int = 0,
    var lastLoginAt: Instant = Instant.now(),
    var notificationCheckedAt: Instant = Instant.now(),
) : BaseEntity()
