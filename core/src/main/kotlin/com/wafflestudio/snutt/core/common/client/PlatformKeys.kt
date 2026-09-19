package com.wafflestudio.snutt.core.common.client

class PlatformKeys(
    config: String,
) {
    private val keys: Map<String, String> =
        config
            .split(",")
            .filter { it.isNotBlank() }
            .associate { entry ->
                val (platform, key) = entry.split(":", limit = 2)
                platform.trim() to key.trim()
            }

    fun matches(
        platform: String?,
        key: String?,
    ): Boolean = platform != null && key != null && keys[platform] == key
}

fun clientInfoOf(
    header: (String) -> String?,
    defaultOsType: String,
): ClientInfo =
    ClientInfo(
        osType = header("x-os-type") ?: defaultOsType,
        osVersion = header("x-os-version"),
        appType = header("x-app-type"),
        appVersion = header("x-app-version"),
        deviceId = header("x-device-id"),
        deviceModel = header("x-device-model"),
        language = Language.from(header("x-language")) ?: Language.KO,
    )
