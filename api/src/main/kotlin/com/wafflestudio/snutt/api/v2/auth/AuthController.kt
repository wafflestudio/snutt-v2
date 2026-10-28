package com.wafflestudio.snutt.api.v2.auth

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.auth.service.TokenPair
import com.wafflestudio.snutt.core.domain.user.service.PasswordResetService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RegisterLocalRequest(
    @field:NotBlank val localId: String,
    @field:NotBlank val password: String,
    @field:NotBlank val email: String,
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
    @field:NotBlank val refreshToken: String,
    val fcmRegistrationId: String? = null,
)

data class RequestPasswordResetRequest(
    @field:NotBlank val localId: String,
)

data class RequestPasswordResetResponse(
    val email: String,
)

data class VerifyPasswordResetCodeRequest(
    @field:NotBlank val localId: String,
    @field:NotBlank val code: String,
)

data class VerifyPasswordResetCodeResponse(
    val resetToken: String,
)

data class ConfirmPasswordResetRequest(
    @field:NotBlank val resetToken: String,
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
        @Valid @RequestBody request: RegisterLocalRequest,
    ): TokenResponse {
        val user = authService.registerLocal(request.localId, request.password, request.email)
        return authService.issueTokens(user).toResponse(user.id!!)
    }

    @Public
    @PostMapping("/login")
    fun loginLocal(
        @Valid @RequestBody request: LoginLocalRequest,
    ): TokenResponse {
        val user = authService.loginLocal(request.localId, request.password)
        return authService.issueTokens(user).toResponse(user.id!!)
    }

    @Public
    @PostMapping("/login/{provider}")
    fun loginSocial(
        @PathVariable provider: AuthProvider,
        @Valid @RequestBody request: SocialLoginRequest,
    ): TokenResponse {
        if (provider == AuthProvider.LOCAL) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val user = authService.loginSocial(provider, request.token)
        return authService.issueTokens(user).toResponse(user.id!!)
    }

    @Public
    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): TokenResponse {
        val (user, tokens) = authService.refresh(request.refreshToken)
        return tokens.toResponse(user.id!!)
    }

    @Public
    @PostMapping("/logout")
    fun logout(
        @Valid @RequestBody request: LogoutRequest,
    ) {
        authService.logout(request.refreshToken, request.fcmRegistrationId)
    }

    @Public
    @PostMapping("/password/reset/request")
    fun requestPasswordReset(
        @Valid @RequestBody body: RequestPasswordResetRequest,
    ): RequestPasswordResetResponse {
        val email = passwordResetService.requestResetByLocalId(body.localId)
        return RequestPasswordResetResponse(email = email)
    }

    @Public
    @PostMapping("/password/reset/verify")
    fun verifyPasswordResetCode(
        @Valid @RequestBody body: VerifyPasswordResetCodeRequest,
    ): VerifyPasswordResetCodeResponse {
        val resetToken = passwordResetService.verifyResetCodeByLocalId(body.localId, body.code)
        return VerifyPasswordResetCodeResponse(resetToken = resetToken)
    }

    @Public
    @PostMapping("/password/reset/confirm")
    fun confirmPasswordReset(
        @Valid @RequestBody body: ConfirmPasswordResetRequest,
    ) {
        passwordResetService.confirmResetWithToken(body.resetToken, body.newPassword)
    }

    @Public
    @PostMapping("/id/find")
    fun findId(
        @Valid @RequestBody body: FindIdRequest,
    ) {
        passwordResetService.sendLocalIdToEmail(body.email)
    }
}
