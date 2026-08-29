package com.wafflestudio.snutt.core.common.client

data class ClientInfo(
    val osType: String,
    val osVersion: String? = null,
    val appType: String? = null,
    val appVersion: String? = null,
    val deviceId: String? = null,
    val deviceModel: String? = null,
    val language: Language = Language.KO,
)
