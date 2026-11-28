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

/**
 * 클라이언트 헤더는 정보성 값이라 `user_device` 컬럼 길이를 넘으면 거부하지 않고 잘라서 쓴다.
 * 자르지 않으면 기기 등록이 DataIntegrityViolation(500)으로 실패한다.
 */
fun clientInfoOf(
    header: (String) -> String?,
    osType: OsType,
): ClientInfo {
    fun truncated(
        name: String,
        maxLength: Int,
    ): String? =
        header(name)?.let { value ->
            // 자른 끝에 서로게이트 쌍의 앞 절반만 남으면 깨진 문자가 되므로 함께 버린다.
            val cut = value.take(maxLength)
            if (cut.length < value.length && cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
        }
    return ClientInfo(
        osType = osType,
        osVersion = truncated("x-os-version", OS_VERSION_MAX_LENGTH),
        appType = truncated("x-app-type", APP_TYPE_MAX_LENGTH),
        appVersion = truncated("x-app-version", APP_VERSION_MAX_LENGTH),
        deviceId = truncated("x-device-id", DEVICE_ID_MAX_LENGTH),
        deviceModel = truncated("x-device-model", DEVICE_MODEL_MAX_LENGTH),
        language = languageOf(header),
    )
}

// user_device 컬럼 길이(V1__init.sql)
private const val OS_VERSION_MAX_LENGTH = 32
private const val APP_TYPE_MAX_LENGTH = 16
private const val APP_VERSION_MAX_LENGTH = 32
private const val DEVICE_ID_MAX_LENGTH = 128
private const val DEVICE_MODEL_MAX_LENGTH = 64
