package com.wafflestudio.snutt.core.domain.user.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

/**
 * 소셜 로그인 연동. 계정이 비활성화되면 행을 삭제해 sub를 재사용 가능하게 둔다(구 active_* 생성 컬럼과 동일한 의미).
 */
@Entity
@Table(name = "user_social_auth")
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
