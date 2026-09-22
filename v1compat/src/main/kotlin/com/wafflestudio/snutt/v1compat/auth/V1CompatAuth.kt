package com.wafflestudio.snutt.v1compat.auth

import com.wafflestudio.snutt.core.common.client.PlatformKeys
import com.wafflestudio.snutt.core.common.client.clientInfoOf
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import tools.jackson.databind.json.JsonMapper

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
    private val platformKeys: PlatformKeys,
    @Value("\${snutt.auth.legacy-secret-key:}") legacySecretKey: String,
    jsonMapper: JsonMapper,
) : HandlerInterceptor {
    private val legacyApiKeyVerifier = LegacyApiKeyVerifier(legacySecretKey, jsonMapper)

    companion object {
        const val CLIENT_INFO_ATTRIBUTE = "v1compat.clientInfo"
        private const val LEGACY_KEY_VERSION = "0"
        private val LEGACY_PLATFORMS = setOf("ios", "web", "android", "test")
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val apiKey = request.getHeader("x-access-apikey")
        val authorized =
            if (apiKey != null) {
                isLegacyApiKey(apiKey)
            } else {
                platformKeys.matches(request.getHeader("x-client-platform"), request.getHeader("x-client-key"))
            }
        if (!authorized) throw SnuttException(ErrorType.WRONG_API_KEY)
        request.setAttribute(CLIENT_INFO_ATTRIBUTE, clientInfoOf(request::getHeader, defaultOsType = "unknown"))
        return true
    }

    private fun isLegacyApiKey(apiKey: String): Boolean {
        val claims = legacyApiKeyVerifier.claimsOf(apiKey) ?: return false
        return claims["string"] in LEGACY_PLATFORMS && claims["key_version"]?.toString() == LEGACY_KEY_VERSION
    }
}
