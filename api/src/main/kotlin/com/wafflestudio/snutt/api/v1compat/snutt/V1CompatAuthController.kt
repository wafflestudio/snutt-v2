package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyLoginResponse
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.PasswordResetService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LegacyLocalRegisterRequest(
    val id: String,
    val password: String,
    val email: String? = null,
)

data class LegacyLocalLoginRequest(
    @com.fasterxml.jackson.annotation.JsonAlias("user_id")
    val id: String,
    val password: String,
)

data class LegacySocialLoginRequest(
    @com.fasterxml.jackson.annotation.JsonAlias("fb_token", "apple_token")
    val token: String,
)

data class LegacyFacebookLoginRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("fb_id")
    val fbId: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("fb_token")
    val fbToken: String,
)

data class LegacyLogoutRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("registration_id")
    val registrationId: String? = null,
)

data class LegacySendEmailRequest(
    @com.fasterxml.jackson.annotation.JsonAlias("user_email")
    val email: String,
)

data class LegacyVerifyResetCodeRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("user_id")
    val localId: String? = null,
    val code: String,
)

data class LegacyResetPasswordRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("user_id")
    val userId: String,
    val password: String,
    val code: String,
)

data class LegacyMaskedEmailRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("user_id")
    val userId: String,
)

// v1 로그인 응답의 token = credentialHash (v1compat 전용, PLAN.md §3)
@RestController
@RequestMapping("/v1/auth")
class V1CompatAuthController(
    private val authService: AuthService,
    private val deviceService: com.wafflestudio.snutt.core.domain.device.service.DeviceService,
    private val passwordResetService: PasswordResetService,
) {
    @Public
    @PostMapping("/register_local")
    fun registerLocal(
        @RequestBody body: LegacyLocalRegisterRequest,
    ): LegacyLoginResponse =
        authService
            .registerLocal(body.id, body.password, body.email)
            .let { (user, _) -> user.toLoginResponse() }

    @Public
    @PostMapping("/login_local")
    fun loginLocal(
        @RequestBody body: LegacyLocalLoginRequest,
    ): LegacyLoginResponse =
        authService
            .loginLocal(body.id, body.password)
            .let { (user, _) -> user.toLoginResponse() }

    @Public
    @PostMapping("/login/facebook", "/login_fb")
    fun loginFacebook(
        @RequestBody body: LegacySocialLoginRequest,
    ): LegacyLoginResponse = socialLogin(AuthProvider.FACEBOOK, body.token)

    @Public
    @PostMapping("/login/google")
    fun loginGoogle(
        @RequestBody body: LegacySocialLoginRequest,
    ): LegacyLoginResponse = socialLogin(AuthProvider.GOOGLE, body.token)

    @Public
    @PostMapping("/login/kakao")
    fun loginKakao(
        @RequestBody body: LegacySocialLoginRequest,
    ): LegacyLoginResponse = socialLogin(AuthProvider.KAKAO, body.token)

    @Public
    @PostMapping("/login/apple", "/login_apple")
    fun loginApple(
        @RequestBody body: LegacySocialLoginRequest,
    ): LegacyLoginResponse = socialLogin(AuthProvider.APPLE, body.token)

    @Public
    @PostMapping("/id/find")
    fun findId(
        @RequestBody body: LegacySendEmailRequest,
    ): Map<String, Any?> {
        passwordResetService.sendLocalIdToEmail(body.email)
        return mapOf("message" to "ok")
    }

    @Public
    @PostMapping("/password/reset/email/send")
    fun sendResetPasswordCode(
        @RequestBody body: LegacySendEmailRequest,
    ): Map<String, Any?> {
        passwordResetService.requestReset(body.email)
        return mapOf("message" to "ok")
    }

    // v1은 아이디(localId)로 초기화한다
    @Public
    @PostMapping("/password/reset/email/check")
    fun getMaskedEmail(
        @RequestBody body: LegacyMaskedEmailRequest,
    ): Map<String, Any?> = mapOf("email" to passwordResetService.getMaskedEmailByLocalId(body.userId))

    @Public
    @PostMapping("/password/reset/verification/code")
    fun verifyResetPasswordCode(
        @RequestBody body: LegacyVerifyResetCodeRequest,
    ): Map<String, Any?> {
        passwordResetService.verifyResetCodeByLocalId(
            body.localId ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
            body.code,
        )
        return mapOf("message" to "ok")
    }

    @Public
    @PostMapping("/password/reset")
    fun resetPassword(
        @RequestBody body: LegacyResetPasswordRequest,
    ): Map<String, Any?> {
        passwordResetService.confirmResetByLocalId(body.userId, body.code, body.password)
        return mapOf("message" to "ok")
    }

    @PostMapping("/logout")
    fun logout(
        @CurrentUser user: User,
        @RequestBody(required = false) body: LegacyLogoutRequest?,
    ): Map<String, Any?> {
        // v1 토큰(credentialHash)은 무상태이므로 등록 토큰만 해제한다 (v1 UserService.logout)
        val registrationId = body?.registrationId
        if (!registrationId.isNullOrBlank()) {
            deviceService.removeRegistrationId(user, registrationId)
        }
        return mapOf("message" to "ok")
    }

    private fun socialLogin(
        provider: AuthProvider,
        token: String,
    ): LegacyLoginResponse =
        authService
            .loginSocial(provider, token)
            .let { (user, _) -> user.toLoginResponse() }

    private fun User.toLoginResponse() = LegacyLoginResponse(userId = externalId, token = credentialHash)
}
