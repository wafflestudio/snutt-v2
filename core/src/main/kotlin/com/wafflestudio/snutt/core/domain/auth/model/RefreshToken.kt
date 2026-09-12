package com.wafflestudio.snutt.core.domain.auth.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * 로그인 하나에 대응하는 refresh token 레코드.
 *
 * refresh 시 새 행을 만들지 않고 이 행의 [tokenHash] 를 제자리에서 교체한다.
 * 따라서 행 하나가 로그인 하나이며, id 는 그 로그인이 끝날 때까지 안정적이다.
 * 로그아웃/탈퇴/비밀번호 변경은 이 행을 삭제하는 것으로 만료를 표현한다.
 */
@Entity
@Table(name = "refresh_token")
class RefreshToken(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    val user: User,
    @Column(nullable = false, columnDefinition = "char(64)")
    var tokenHash: String,
    var expiresAt: Instant,
) : BaseEntity()
