package com.wafflestudio.snutt.core.common.client

// 클라이언트 식별 헤더 집합 (x-os-type, x-os-version, x-app-type, x-app-version, x-device-id, x-device-model)
data class ClientInfo(
    val osType: String,
    val osVersion: String? = null,
    val appType: String? = null,
    val appVersion: String? = null,
    val deviceId: String? = null,
    val deviceModel: String? = null,
)
