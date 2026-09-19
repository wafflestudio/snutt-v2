package com.wafflestudio.snutt.core.domain.theme.model

import jakarta.persistence.Entity
import jakarta.persistence.Id

@Entity
class UserPreference(
    @Id
    val userId: Long,
    var defaultThemeId: Long,
)
