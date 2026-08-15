package com.wafflestudio.snutt.v1compat.auth

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class V1Public

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class V1AdminOnly

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class V1EmailVerifiedRequired

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class V1CurrentUser

@Component
class V1UserAuthInterceptor(
    private val legacyTokenService: LegacyTokenService,
) : HandlerInterceptor {
    companion object {
        const val USER_ATTRIBUTE = "v1compat.user"
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod) return true
        if (handler.has(V1Public::class.java)) return true

        val token = request.getHeader("x-access-token") ?: throw SnuttException(ErrorType.NO_USER_TOKEN)
        val user = legacyTokenService.authenticate(token)
        if (handler.has(V1AdminOnly::class.java) && !user.isAdmin) throw SnuttException(ErrorType.USER_NOT_ADMIN)
        if (handler.has(V1EmailVerifiedRequired::class.java) && !user.isEmailVerified) {
            throw SnuttException(ErrorType.USER_EMAIL_IS_NOT_VERIFIED)
        }
        request.setAttribute(USER_ATTRIBUTE, user)
        return true
    }

    private fun HandlerMethod.has(annotation: Class<out Annotation>) =
        hasMethodAnnotation(annotation) || beanType.isAnnotationPresent(annotation)
}

@Component
class V1ApiKeyInterceptor(
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

    companion object {
        const val CLIENT_INFO_ATTRIBUTE = "v1compat.clientInfo"
        private const val PLATFORM_HEADER = "x-client-platform"
        private const val KEY_HEADER = "x-client-key"
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val apiKey = request.getHeader("x-access-apikey")
        if (apiKey != null) {
            if (apiKey !in platformKeys.values) throw SnuttException(ErrorType.WRONG_API_KEY)
        } else {
            val platform = request.getHeader(PLATFORM_HEADER) ?: throw SnuttException(ErrorType.WRONG_API_KEY)
            val key = request.getHeader(KEY_HEADER) ?: throw SnuttException(ErrorType.WRONG_API_KEY)
            if (platformKeys[platform] != key) throw SnuttException(ErrorType.WRONG_API_KEY)
        }
        request.setAttribute(CLIENT_INFO_ATTRIBUTE, request.toClientInfo())
        return true
    }

    private fun HttpServletRequest.toClientInfo() =
        ClientInfo(
            osType = getHeader("x-os-type") ?: "unknown",
            osVersion = getHeader("x-os-version"),
            appType = getHeader("x-app-type"),
            appVersion = getHeader("x-app-version"),
            deviceId = getHeader("x-device-id"),
            deviceModel = getHeader("x-device-model"),
            language = Language.from(getHeader("x-language")) ?: Language.KO,
        )
}
