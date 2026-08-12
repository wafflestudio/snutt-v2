package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyLoginResponse
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.user.model.User
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
    val id: String,
    val password: String,
)

data class LegacySocialLoginRequest(
    val token: String,
)

data class LegacyFacebookLoginRequest(
    val fbToken: String,
)

data class LegacyLogoutRequest(
    val fcmRegistrationId: String? = null,
)

// v1 로그인 응답의 token = credentialHash (v1compat 전용, PLAN.md §3)
@RestController
@RequestMapping("/v1/auth", "/auth")
class V1CompatAuthController(
    private val authService: AuthService,
    private val userDeviceRepository: UserDeviceRepository,
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

    @PostMapping("/logout")
    fun logout(
        @CurrentUser user: User,
        @RequestBody(required = false) body: LegacyLogoutRequest?,
    ) {
        // v1 토큰(credentialHash)은 무상태이므로 FCM 기기만 해제한다 (v1 동일)
        body?.fcmRegistrationId?.let { fcmRegistrationId ->
            userDeviceRepository
                .findByUserIdAndFcmRegistrationIdAndIsDeletedFalse(user.id!!, fcmRegistrationId)
                ?.let { it.isDeleted = true }
        }
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
