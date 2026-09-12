package com.wafflestudio.snutt.core.domain.theme.dto

import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeKind

data class TimetableThemeDisplay(
    val id: Long,
    val userId: Long?,
    val name: String,
    val colors: List<ColorSet>,
    val kind: ThemeKind,
    val builtinCode: String?,
    val publicationId: Long?,
    val isDefault: Boolean,
)

data class ThemePublicationDisplay(
    val id: Long,
    val sourceThemeId: Long?,
    val authorId: Long?,
    val name: String,
    val colors: List<ColorSet>,
    val authorAnonymous: Boolean,
    val authorNickname: String?,
    val listed: Boolean,
    val downloadCount: Long,
)
