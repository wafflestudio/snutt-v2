package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.api.auth.EmailVerifiedRequired
import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

// v1 호환: x-access-token(credentialHash) 인증. v2 JWT 경로와 공존한다 (PLAN.md §3 인증)
@Component
class V1CompatUserAuthInterceptor(
    private val authService: AuthService,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod) return true
        val isPublic =
            handler.hasMethodAnnotation(Public::class.java) ||
                handler.beanType.isAnnotationPresent(Public::class.java)
        if (isPublic) return true

        val token =
            request.getHeader("x-access-token") ?: throw SnuttException(ErrorType.NO_USER_TOKEN)
        val user = authService.authenticateLegacyToken(token)
        val isEmailVerifiedRequired =
            handler.hasMethodAnnotation(EmailVerifiedRequired::class.java) ||
                handler.beanType.isAnnotationPresent(EmailVerifiedRequired::class.java)
        if (isEmailVerifiedRequired && !user.isEmailVerified) throw SnuttException(ErrorType.USER_EMAIL_IS_NOT_VERIFIED)
        request.setAttribute(com.wafflestudio.snutt.api.auth.UserAuthInterceptor.USER_ATTRIBUTE, user)
        return true
    }
}

// v1 호환: x-access-apikey + x-client-platform/x-client-key 둘 다 수용 (v2 키 목록으로 검증)
@Component
class V1CompatApiKeyInterceptor(
    @param:Value("\${snutt.auth.platform-keys}") platformKeysConfig: String,
) : HandlerInterceptor {
    private val platformKeys: Map<String, String> =
        platformKeysConfig
            .split(",")
            .filter { it.isNotBlank() }
            .associate { entry ->
                val (platform, key) = entry.split(":", limit = 2)
                platform.trim() to key.trim()
            }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val apiKey = request.getHeader("x-access-apikey")
        if (apiKey != null) {
            if (apiKey !in platformKeys.values) throw SnuttException(ErrorType.WRONG_API_KEY)
            request.setClientInfo()
            return true
        }
        val platform =
            request.getHeader(com.wafflestudio.snutt.api.auth.PlatformKeyInterceptor.PLATFORM_HEADER)
                ?: throw SnuttException(ErrorType.WRONG_API_KEY)
        val key =
            request.getHeader(com.wafflestudio.snutt.api.auth.PlatformKeyInterceptor.KEY_HEADER)
                ?: throw SnuttException(ErrorType.WRONG_API_KEY)
        if (platformKeys[platform] != key) throw SnuttException(ErrorType.WRONG_API_KEY)
        request.setClientInfo()
        return true
    }

    private fun HttpServletRequest.setClientInfo() {
        setAttribute(
            com.wafflestudio.snutt.api.auth.PlatformKeyInterceptor.CLIENT_INFO_ATTRIBUTE,
            com.wafflestudio.snutt.core.common.client.ClientInfo(
                osType = getHeader("x-os-type") ?: "unknown",
                osVersion = getHeader("x-os-version"),
                appType = getHeader("x-app-type"),
                appVersion = getHeader("x-app-version"),
                deviceId = getHeader("x-device-id"),
                deviceModel = getHeader("x-device-model"),
                language =
                    com.wafflestudio.snutt.core.common.client.Language
                        .from(getHeader("x-language")) ?: com.wafflestudio.snutt.core.common.client.Language.KO,
            ),
        )
    }
}

// v1 엔드포인트의 Deprecation/Sunset/Link 헤더 (PLAN.md §6 헤더)
@Component
class DeprecationHeaderInterceptor(
    @param:Value("\${snutt.v1-sunset:2027-12-31}") private val sunsetDate: String,
    @param:Value("\${snutt.v2-base-url:https://snutt.wafflestudio.com}") private val v2BaseUrl: String,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        response.setHeader("Deprecation", "true")
        response.setHeader("Sunset", sunsetDate)
        response.setHeader("Link", "<$v2BaseUrl/v2>; rel=\"successor-version\"")
        return true
    }
}
