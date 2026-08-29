package com.wafflestudio.snutt.core.domain.theme.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 사용자별 기본 테마. 기본 테마 지정을 theme.updatedAt 오염 대신 명시 테이블로 분리한다.
 */
@Entity
@Table(name = "user_preference")
class UserPreference(
    @Id
    val userId: Long,
    @Column(name = "default_theme_id", nullable = false)
    var defaultThemeId: Long,
)
