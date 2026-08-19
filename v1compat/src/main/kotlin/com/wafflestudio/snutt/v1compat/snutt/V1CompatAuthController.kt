package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.device.service.DeviceService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.PasswordResetService
import com.wafflestudio.snutt.v1compat.auth.LegacyTokenService
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1Public
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyLoginResponse
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyOkResponse
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
    @param:JsonAlias("user_id")
    val id: String,
    val password: String,
)

data class LegacySocialLoginRequest(
    @param:JsonAlias("fb_token", "apple_token")
    val token: String,
)

data class LegacyFacebookLoginRequest(
    @param:JsonProperty("fb_id")
    val fbId: String? = null,
    @param:JsonProperty("fb_token")
    val fbToken: String,
)

data class LegacyLogoutRequest(
    @param:JsonProperty("registration_id")
    val registrationId: Long? = null,
)

data class LegacySendEmailRequest(
    @param:JsonAlias("user_email")
    val email: String,
)

data class LegacyVerifyResetCodeRequest(
    @param:JsonProperty("user_id")
    val localId: String? = null,
    val code: String,
)

data class LegacyResetPasswordRequest(
    @param:JsonProperty("user_id")
    val userId: String,
    val password: String,
    val code: String,
)

data class LegacyMaskedEmailRequest(
    @param:JsonProperty("user_id")
    val userId: String,
)

data class LegacyTokenExchangeRequest(
    val legacyToken: String,
)

data class LegacyTokenExchangeResponse(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
)

data class LegacyMaskedEmailResponse(
    val email: String,
)

@RestController
@RequestMapping("/v1/auth")
class V1CompatAuthController(
    private val authService: AuthService,
    private val legacyTokenService: LegacyTokenService,
    private val deviceService: DeviceService,
    private val passwordResetService: PasswordResetService,
) {
    @V1Public
    @PostMapping("/register_local")
    fun registerLocal(
        @RequestBody body: LegacyLocalRegisterRequest,
    ): LegacyLoginResponse = authService.registerLocal(body.id, body.password, body.email).toLoginResponse()

    @V1Public
    @PostMapping("/login_local")
    fun loginLocal(
        @RequestBody body: LegacyLocalLoginRequest,
    ): LegacyLoginResponse = authService.loginLocal(body.id, body.password).toLoginResponse()

    @V1Public
    @PostMapping("/login/facebook", "/login_fb")
    fun loginFacebook(
        @RequestBody body: LegacySocialLoginRequest,
    ): LegacyLoginResponse = socialLogin(AuthProvider.FACEBOOK, body.token)

    @V1Public
    @PostMapping("/login/google")
    fun loginGoogle(
        @RequestBody body: LegacySocialLoginRequest,
    ): LegacyLoginResponse = socialLogin(AuthProvider.GOOGLE, body.token)

    @V1Public
    @PostMapping("/login/kakao")
    fun loginKakao(
        @RequestBody body: LegacySocialLoginRequest,
    ): LegacyLoginResponse = socialLogin(AuthProvider.KAKAO, body.token)

    @V1Public
    @PostMapping("/login/apple", "/login_apple")
    fun loginApple(
        @RequestBody body: LegacySocialLoginRequest,
    ): LegacyLoginResponse = socialLogin(AuthProvider.APPLE, body.token)

    @V1Public
    @PostMapping("/token/exchange")
    fun exchangeLegacyToken(
        @RequestBody body: LegacyTokenExchangeRequest,
    ): LegacyTokenExchangeResponse {
        val user = legacyTokenService.authenticate(body.legacyToken)
        val tokens = authService.issueTokens(user)
        return LegacyTokenExchangeResponse(
            userId = user.id!!.toString(),
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
        )
    }

    @V1Public
    @PostMapping("/id/find")
    fun findId(
        @RequestBody body: LegacySendEmailRequest,
    ): LegacyOkResponse {
        passwordResetService.sendLocalIdToEmail(body.email)
        return LegacyOkResponse()
    }

    @V1Public
    @PostMapping("/password/reset/email/send")
    fun sendResetPasswordCode(
        @RequestBody body: LegacySendEmailRequest,
    ): LegacyOkResponse {
        passwordResetService.requestReset(body.email)
        return LegacyOkResponse()
    }

    @V1Public
    @PostMapping("/password/reset/email/check")
    fun getMaskedEmail(
        @RequestBody body: LegacyMaskedEmailRequest,
    ): LegacyMaskedEmailResponse = LegacyMaskedEmailResponse(email = passwordResetService.getMaskedEmailByLocalId(body.userId))

    @V1Public
    @PostMapping("/password/reset/verification/code")
    fun verifyResetPasswordCode(
        @RequestBody body: LegacyVerifyResetCodeRequest,
    ): LegacyOkResponse {
        passwordResetService.verifyResetCodeByLocalId(
            body.localId ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
            body.code,
        )
        return LegacyOkResponse()
    }

    @V1Public
    @PostMapping("/password/reset")
    fun resetPassword(
        @RequestBody body: LegacyResetPasswordRequest,
    ): LegacyOkResponse {
        passwordResetService.confirmResetByLocalId(body.userId, body.code, body.password)
        return LegacyOkResponse()
    }

    @PostMapping("/logout")
    fun logout(
        @V1CurrentUser user: User,
        @RequestBody(required = false) body: LegacyLogoutRequest?,
    ): LegacyOkResponse {
        val registrationId = body?.registrationId
        if (!registrationId.isNullOrBlank()) {
            deviceService.removeRegistrationId(user, registrationId)
        }
        return LegacyOkResponse()
    }

    private fun socialLogin(
        provider: AuthProvider,
        token: String,
    ): LegacyLoginResponse = authService.loginSocial(provider, token).toLoginResponse()

    private fun User.toLoginResponse() = LegacyLoginResponse(userId = externalId, token = legacyTokenService.issue(this))
}
