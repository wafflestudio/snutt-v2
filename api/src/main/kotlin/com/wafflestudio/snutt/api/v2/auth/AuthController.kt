package com.wafflestudio.snutt.api.v2.auth

import com.wafflestudio.snutt.api.auth.CurrentSessionId
import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.auth.service.TokenPair
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RegisterLocalRequest(
    @field:NotBlank val localId: String,
    @field:NotBlank val password: String,
    val email: String? = null,
)

data class LoginLocalRequest(
    @field:NotBlank val localId: String,
    @field:NotBlank val password: String,
)

data class SocialLoginRequest(
    @field:NotBlank val token: String,
)

data class RefreshRequest(
    @field:NotBlank val refreshToken: String,
)

data class LogoutRequest(
    val fcmRegistrationId: String? = null,
)

data class LegacyTokenExchangeRequest(
    @field:NotBlank val legacyToken: String,
)

data class TokenResponse(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
)

private fun TokenPair.toResponse(userExternalId: String) =
    TokenResponse(
        userId = userExternalId,
        accessToken = accessToken,
        refreshToken = refreshToken,
    )

@RestController
@RequestMapping("/v2/auth")
class AuthController(
    private val authService: AuthService,
) {
    @Public
    @PostMapping("/register")
    fun registerLocal(
        @RequestBody request: RegisterLocalRequest,
    ): TokenResponse {
        val (user, tokens) = authService.registerLocal(request.localId, request.password, request.email)
        return tokens.toResponse(user.externalId)
    }

    @Public
    @PostMapping("/login")
    fun loginLocal(
        @RequestBody request: LoginLocalRequest,
    ): TokenResponse {
        val (user, tokens) = authService.loginLocal(request.localId, request.password)
        return tokens.toResponse(user.externalId)
    }

    @Public
    @PostMapping("/login/{provider}")
    fun loginSocial(
        @PathVariable provider: String,
        @RequestBody request: SocialLoginRequest,
    ): TokenResponse {
        val authProvider =
            AuthProvider.from(provider)?.takeIf { it != AuthProvider.LOCAL }
                ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        val (user, tokens) = authService.loginSocial(authProvider, request.token)
        return tokens.toResponse(user.externalId)
    }

    @Public
    @PostMapping("/refresh")
    fun refresh(
        @RequestBody request: RefreshRequest,
    ): TokenResponse {
        val (user, tokens) = authService.refresh(request.refreshToken)
        return tokens.toResponse(user.externalId)
    }

    @PostMapping("/logout")
    fun logout(
        @CurrentSessionId sessionId: String,
        @RequestBody(required = false) request: LogoutRequest?,
    ) {
        authService.logout(sessionId, request?.fcmRegistrationId)
    }

    // 구 클라이언트 업그레이드 경로: v1 credentialHash → v2 토큰 쌍 (PLAN.md §3 인증)
    @Public
    @PostMapping("/token/exchange")
    fun exchangeLegacyToken(
        @RequestBody request: LegacyTokenExchangeRequest,
    ): TokenResponse {
        val (user, tokens) = authService.exchangeLegacyToken(request.legacyToken)
        return tokens.toResponse(user.externalId)
    }
}
