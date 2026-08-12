package com.wafflestudio.snutt.api.auth

// 클라이언트 식별 헤더 집합 (v1 ClientInfoWebFilter 이식)
data class ClientInfo(
    val osType: String,
    val osVersion: String?,
    val appVersion: String?,
    val deviceModel: String?,
)
