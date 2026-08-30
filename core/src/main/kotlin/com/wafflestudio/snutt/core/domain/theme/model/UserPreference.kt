package com.wafflestudio.snutt.core.domain.theme.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "user_preference")
class UserPreference(
    @Id
    val userId: Long,
    @Column(name = "default_theme_id", nullable = false)
    var defaultThemeId: Long,
)
