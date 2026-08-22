package com.wafflestudio.snutt.v1compat.auth

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.MacAlgorithm
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import javax.crypto.spec.SecretKeySpec

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
    @Value("\${snutt.auth.platform-keys}") platformKeysConfig: String,
    @Value("\${snutt.auth.legacy-secret-key:}") legacySecretKey: String,
) : HandlerInterceptor {
    private val platformKeys: Map<String, String> =
        platformKeysConfig
            .split(",")
            .filter { it.isNotBlank() }
            .associate { entry ->
                val (platform, key) = entry.split(":", limit = 2)
                platform.trim() to key.trim()
            }

    // 구 백엔드는 짧은 secret으로 HS256 apikey JWT를 발급했다. jjwt의 최소 키 길이 제한(256비트)을
    // 우회한 구 검증과 동일하게 80비트까지만 허용한다. secret이 비었으면 구 apikey는 받지 않는다.
    private val legacyApiKeyParser =
        if (legacySecretKey.isBlank()) {
            null
        } else {
            Jwts
                .parser()
                .verifyWith(SecretKeySpec(legacySecretKey.toByteArray(), "HmacSHA256"))
                .sig()
                .remove(Jwts.SIG.HS256)
                .add(shortKeyTolerantHs256())
                .and()
                .build()
        }

    companion object {
        const val CLIENT_INFO_ATTRIBUTE = "v1compat.clientInfo"
        private const val PLATFORM_HEADER = "x-client-platform"
        private const val KEY_HEADER = "x-client-key"
        private val LEGACY_KEY_VERSIONS = mapOf("ios" to "0", "web" to "0", "android" to "0", "test" to "0")

        private fun shortKeyTolerantHs256(): MacAlgorithm {
            val minKeyBitLength = 80
            return Class
                .forName("io.jsonwebtoken.impl.security.DefaultMacAlgorithm")
                .getDeclaredConstructor(String::class.java, String::class.java, Int::class.java)
                .apply { isAccessible = true }
                .newInstance("HS256", "HmacSHA256", minKeyBitLength) as MacAlgorithm
        }
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val apiKey = request.getHeader("x-access-apikey")
        if (apiKey != null) {
            if (apiKey !in platformKeys.values && !isLegacyApiKey(apiKey)) throw SnuttException(ErrorType.WRONG_API_KEY)
        } else {
            val platform = request.getHeader(PLATFORM_HEADER) ?: throw SnuttException(ErrorType.WRONG_API_KEY)
            val key = request.getHeader(KEY_HEADER) ?: throw SnuttException(ErrorType.WRONG_API_KEY)
            if (platformKeys[platform] != key) throw SnuttException(ErrorType.WRONG_API_KEY)
        }
        request.setAttribute(CLIENT_INFO_ATTRIBUTE, request.toClientInfo())
        return true
    }

    private fun isLegacyApiKey(apiKey: String): Boolean {
        val parser = legacyApiKeyParser ?: return false
        return try {
            val claims = parser.parseSignedClaims(apiKey).payload
            val platform = claims["string"]?.toString()
            val keyVersion = claims["key_version"]?.toString()
            LEGACY_KEY_VERSIONS[platform] == keyVersion
        } catch (e: Exception) {
            false
        }
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
