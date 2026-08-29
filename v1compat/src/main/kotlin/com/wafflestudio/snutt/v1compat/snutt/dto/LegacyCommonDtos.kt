package com.wafflestudio.snutt.v1compat.snutt.dto

data class LegacyOkResponse(
    val message: String = "ok",
)

data class LegacyPageResponse<T>(
    val content: List<T>,
    val totalCount: Int,
)
