package com.wafflestudio.snutt.core.domain.theme.model

// 테마 색상 (JSON). null = 클라이언트 내장 색상 사용
data class ColorSet(
    var backgroundColor: String? = null,
    var foregroundColor: String? = null,
)
