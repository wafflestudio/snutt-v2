package com.wafflestudio.snutt.api.v2.auth

import com.wafflestudio.snutt.api.auth.CurrentSessionId
import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.auth.service.TokenPair
import com.wafflestudio.snutt.core.domain.user.service.PasswordResetService
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

data class RequestPasswordResetRequest(
    @field:NotBlank val email: String,
)

data class ConfirmPasswordResetRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val code: String,
    @field:NotBlank val newPassword: String,
)

data class FindIdRequest(
    @field:NotBlank val email: String,
)

data class TokenResponse(
    val userId: Long,
    val accessToken: String,
    val refreshToken: String,
)

private fun TokenPair.toResponse(userId: Long) =
    TokenResponse(
        userId = userId,
        accessToken = accessToken,
        refreshToken = refreshToken,
    )

@RestController
@RequestMapping("/v2/auth")
class AuthController(
    private val authService: AuthService,
    private val passwordResetService: PasswordResetService,
) {
    @Public
    @PostMapping("/register")
    fun registerLocal(
        @RequestBody request: RegisterLocalRequest,
    ): TokenResponse {
        val user = authService.registerLocal(request.localId, request.password, request.email)
        return authService.issueTokens(user).toResponse(user.id!!)
    }

    @Public
    @PostMapping("/login")
    fun loginLocal(
        @RequestBody request: LoginLocalRequest,
    ): TokenResponse {
        val user = authService.loginLocal(request.localId, request.password)
        return authService.issueTokens(user).toResponse(user.id!!)
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
        val user = authService.loginSocial(authProvider, request.token)
        return authService.issueTokens(user).toResponse(user.id!!)
    }

    @Public
    @PostMapping("/refresh")
    fun refresh(
        @RequestBody request: RefreshRequest,
    ): TokenResponse {
        val (user, tokens) = authService.refresh(request.refreshToken)
        return tokens.toResponse(user.id!!)
    }

    @PostMapping("/logout")
    fun logout(
        @CurrentSessionId sessionId: Long,
        @RequestBody(required = false) request: LogoutRequest?,
    ) {
        authService.logout(sessionId, request?.fcmRegistrationId)
    }

    @Public
    @PostMapping("/password/reset/request")
    fun requestPasswordReset(
        @RequestBody body: RequestPasswordResetRequest,
    ) {
        passwordResetService.requestReset(body.email)
    }

    @Public
    @PostMapping("/password/reset/confirm")
    fun confirmPasswordReset(
        @RequestBody body: ConfirmPasswordResetRequest,
    ) {
        passwordResetService.confirmReset(body.email, body.code, body.newPassword)
    }

    @Public
    @PostMapping("/id/find")
    fun findId(
        @RequestBody body: FindIdRequest,
    ) {
        passwordResetService.sendLocalIdToEmail(body.email)
    }
}
