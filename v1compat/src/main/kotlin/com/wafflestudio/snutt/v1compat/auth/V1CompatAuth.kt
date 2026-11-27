package com.wafflestudio.snutt.v1compat.auth

import com.wafflestudio.snutt.core.common.client.CLIENT_INFO_ATTRIBUTE
import com.wafflestudio.snutt.core.common.client.OsType
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
import java.time.Clock

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

        val token = request.getHeader("x-access-token") ?: throw SnuttException(ErrorType.MISSING_ACCESS_TOKEN)
        val user = legacyTokenService.authenticate(token)
        if (handler.has(V1AdminOnly::class.java) && !user.isAdmin) throw SnuttException(ErrorType.USER_NOT_ADMIN)
        // v1 EvService는 isEmailVerified == false일 때만 막았다. 인증 여부가 없던(null) 이관 계정은 통과한다.
        if (handler.has(V1EmailVerifiedRequired::class.java) && user.isEmailVerified == false) {
            throw SnuttException(ErrorType.EMAIL_NOT_VERIFIED)
        }
        request.setAttribute(USER_ATTRIBUTE, user)
        return true
    }

    private fun HandlerMethod.has(annotation: Class<out Annotation>) =
        hasMethodAnnotation(annotation) || beanType.isAnnotationPresent(annotation)
}

@Component
class V1ApiKeyInterceptor(
    @Value("\${snutt.auth.legacy-secret-key}") legacySecretKey: String,
    jsonMapper: JsonMapper,
    clock: Clock,
) : HandlerInterceptor {
    private val legacyApiKeyVerifier = LegacyApiKeyVerifier(legacySecretKey, jsonMapper, clock)

    companion object {
        private const val LEGACY_KEY_VERSION = "0"
        private val LEGACY_PLATFORMS = setOf("ios", "web", "android", "test")
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val apiKey = request.getHeader("x-access-apikey")
        if (apiKey == null || !isLegacyApiKey(apiKey)) throw SnuttException(ErrorType.WRONG_API_KEY)
        request.setAttribute(
            CLIENT_INFO_ATTRIBUTE,
            clientInfoOf(
                request::getHeader,
                OsType.from(request.getHeader("x-os-type")) ?: OsType.UNKNOWN,
            ),
        )
        return true
    }

    private fun isLegacyApiKey(apiKey: String): Boolean {
        val claims = legacyApiKeyVerifier.claimsOf(apiKey) ?: return false
        return claims["string"] in LEGACY_PLATFORMS && claims["key_version"]?.toString() == LEGACY_KEY_VERSION
    }
}
