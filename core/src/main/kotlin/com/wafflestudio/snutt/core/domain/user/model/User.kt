package com.wafflestudio.snutt.core.domain.user.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "`user`")
class User(
    var email: String? = null,
    var isEmailVerified: Boolean = false,
    var nickname: String,
    var localId: String? = null,
    var localPw: String? = null,
    var active: Boolean = true,
    var isAdmin: Boolean = false,
    var lastLoginAt: Instant = Instant.now(),
    var notificationCheckedAt: Instant = Instant.now(),
) : BaseEntity() {
    val nicknameWithoutTag: String
        get() = nickname.substringBeforeLast(NICKNAME_TAG_DELIMITER)

    val nicknameTag: Int?
        get() = nickname.substringAfterLast(NICKNAME_TAG_DELIMITER, "").toIntOrNull()

    companion object {
        const val NICKNAME_TAG_DELIMITER = "#"
    }
}
