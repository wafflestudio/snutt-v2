package com.wafflestudio.snutt.core.common.client

class PlatformKeys(
    config: String,
) {
    private val keys: Map<OsType, String> =
        config
            .split(",")
            .filter { it.isNotBlank() }
            .associate { entry ->
                val (platform, key) = entry.split(":", limit = 2)
                OsType.fromValue(platform.trim()) to key.trim()
            }

    fun matches(
        osType: OsType,
        key: String?,
    ): Boolean = key != null && keys[osType] == key
}

fun clientInfoOf(
    header: (String) -> String?,
    osType: OsType,
): ClientInfo =
    ClientInfo(
        osType = osType,
        osVersion = header("x-os-version"),
        appType = header("x-app-type"),
        appVersion = header("x-app-version"),
        deviceId = header("x-device-id"),
        deviceModel = header("x-device-model"),
        language = languageOf(header),
    )
