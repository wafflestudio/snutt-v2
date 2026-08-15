package com.wafflestudio.snutt.core.domain.theme.dto

import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus

data class TimetableThemeDisplay(
    val id: String?,
    val name: String,
    val colorList: List<ColorSet>?,
    val isCustom: Boolean,
    val status: ThemeStatus,
    val isDefault: Boolean,
    val publishName: String?,
    val authorAnonymous: Boolean?,
    val downloadCount: Long,
    val authorNickname: String?,
)
