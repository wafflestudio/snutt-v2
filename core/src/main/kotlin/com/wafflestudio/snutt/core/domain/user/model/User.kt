package com.wafflestudio.snutt.core.domain.user.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
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
    var facebookSub: String? = null,
    var facebookName: String? = null,
    var appleSub: String? = null,
    var appleTransferSub: String? = null,
    var appleEmail: String? = null,
    var googleSub: String? = null,
    var googleEmail: String? = null,
    var kakaoSub: String? = null,
    var kakaoEmail: String? = null,
    var active: Boolean = true,
    var isAdmin: Boolean = false,
    var lastLoginAt: Instant = Instant.now(),
    var notificationCheckedAt: Instant = Instant.now(),
) : BaseEntity() {
    val nicknameWithoutTag: String
        get() = nickname.substringBeforeLast(NICKNAME_TAG_DELIMITER)

    val nicknameTag: Int?
        get() = nickname.substringAfterLast(NICKNAME_TAG_DELIMITER, "").toIntOrNull()

    val authProviders: List<AuthProvider>
        get() =
            buildList {
                if (localId != null) add(AuthProvider.LOCAL)
                if (facebookSub != null) add(AuthProvider.FACEBOOK)
                if (appleSub != null) add(AuthProvider.APPLE)
                if (googleSub != null) add(AuthProvider.GOOGLE)
                if (kakaoSub != null) add(AuthProvider.KAKAO)
            }

    companion object {
        const val NICKNAME_TAG_DELIMITER = "#"
    }
}
