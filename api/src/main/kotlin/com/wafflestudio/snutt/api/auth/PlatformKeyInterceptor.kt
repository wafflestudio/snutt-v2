package com.wafflestudio.snutt.api.auth

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class PlatformKeyInterceptor(
    @Value("\${snutt.auth.platform-keys}") platformKeysConfig: String,
) : HandlerInterceptor {
    private val platformKeys: Map<String, String> =
        platformKeysConfig
            .split(",")
            .filter { it.isNotBlank() }
            .associate { entry ->
                val (platform, key) = entry.split(":", limit = 2)
                platform.trim() to key.trim()
            }

    companion object {
        const val PLATFORM_HEADER = "x-client-platform"
        const val KEY_HEADER = "x-client-key"
        const val PLATFORM_ATTRIBUTE = "clientPlatform"
        const val CLIENT_INFO_ATTRIBUTE = "clientInfo"
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val platform = request.getHeader(PLATFORM_HEADER) ?: throw SnuttException(ErrorType.WRONG_API_KEY)
        val key = request.getHeader(KEY_HEADER) ?: throw SnuttException(ErrorType.WRONG_API_KEY)
        if (platformKeys[platform] != key) throw SnuttException(ErrorType.WRONG_API_KEY)
        request.setAttribute(PLATFORM_ATTRIBUTE, platform)
        request.setAttribute(
            CLIENT_INFO_ATTRIBUTE,
            ClientInfo(
                osType = request.getHeader("x-os-type") ?: platform,
                osVersion = request.getHeader("x-os-version"),
                appType = request.getHeader("x-app-type"),
                appVersion = request.getHeader("x-app-version"),
                deviceId = request.getHeader("x-device-id"),
                deviceModel = request.getHeader("x-device-model"),
                language = Language.from(request.getHeader("x-language")) ?: Language.KO,
            ),
        )
        return true
    }
}
