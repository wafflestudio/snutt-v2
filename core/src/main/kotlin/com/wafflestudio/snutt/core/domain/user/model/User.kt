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
    var isEmailVerified: Boolean = false,
    var nickname: String,
    @Column(nullable = false, length = 4)
    var nicknameTag: String,
    var localId: String? = null,
    var localPw: String? = null,
    var active: Boolean = true,
    var isAdmin: Boolean = false,
    var lastLoginAt: Instant = Instant.now(),
    var notificationCheckedAt: Instant = Instant.now(),
) : BaseEntity() {
    val fullNickname: String
        get() = "$nickname#$nicknameTag"
}
