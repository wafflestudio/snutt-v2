package com.wafflestudio.snutt.api.v2.user

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.EmailVerificationService
import com.wafflestudio.snutt.core.domain.user.service.UserService
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class UserResponse(
    val id: String,
    val nickname: String,
    val nicknameTag: Int?,
    val email: String?,
    val isEmailVerified: Boolean,
    val authProviders: List<String>,
    val isAdmin: Boolean,
)

data class UpdateUserRequest(
    @field:NotBlank val nickname: String,
)

private fun User.toResponse() =
    UserResponse(
        id = externalId,
        nickname = nicknameWithoutTag,
        nicknameTag = nicknameTag,
        email = email,
        isEmailVerified = isEmailVerified,
        authProviders = authProviders.map { it.value },
        isAdmin = isAdmin,
    )

@RestController
@RequestMapping("/v2/users")
class UserController(
    private val userService: UserService,
    private val emailVerificationService: EmailVerificationService,
    private val authService: AuthService,
) {
    @GetMapping("/me")
    fun getMe(
        @CurrentUser user: User,
    ): UserResponse = user.toResponse()

    @PatchMapping("/me")
    fun updateMe(
        @CurrentUser user: User,
        @RequestBody request: UpdateUserRequest,
    ): UserResponse = userService.updateNickname(user, request.nickname).toResponse()

    @DeleteMapping("/me")
    fun deleteMe(
        @CurrentUser user: User,
    ) {
        userService.deactivate(user)
    }

    // 이메일 인증 (v1 이식: SNU 메일 6자리 코드, 3분 TTL)
    data class SendVerificationEmailRequest(
        val email: String,
    )

    data class VerificationCodeRequest(
        val code: String,
    )

    data class EmailVerificationResultResponse(
        val isEmailVerified: Boolean,
    )

    @PostMapping("/me/email/verification")
    fun sendVerificationEmail(
        @CurrentUser user: User,
        @RequestBody body: SendVerificationEmailRequest,
    ) {
        emailVerificationService.sendVerificationCode(user, body.email)
    }

    @GetMapping("/me/email/verification")
    fun getEmailVerification(
        @CurrentUser user: User,
    ): EmailVerificationResultResponse = EmailVerificationResultResponse(user.isEmailVerified)

    @DeleteMapping("/me/email/verification")
    fun resetEmailVerification(
        @CurrentUser user: User,
    ): EmailVerificationResultResponse {
        emailVerificationService.resetEmailVerification(user)
        return EmailVerificationResultResponse(false)
    }

    @PostMapping("/me/email/verification/code")
    fun confirmEmailVerification(
        @CurrentUser user: User,
        @RequestBody body: VerificationCodeRequest,
    ): EmailVerificationResultResponse {
        emailVerificationService.verifyEmail(user, body.code)
        return EmailVerificationResultResponse(true)
    }

    // 계정 관리: 로컬 계정 연결 / 비밀번호 변경 / 소셜 연동·해제
    data class AttachLocalRequest(
        val localId: String,
        val password: String,
    )

    data class ChangePasswordRequest(
        val currentPassword: String,
        val newPassword: String,
    )

    data class ChangePasswordResponse(
        val accessToken: String,
        val refreshToken: String,
    )

    data class SocialTokenRequest(
        val token: String,
    )

    /** 자격 증명을 바꾼 뒤 남아 있는 로그인 수단 */
    data class AuthProvidersResponse(
        val authProviders: List<AuthProvider>,
    )

    @PostMapping("/me/password")
    fun attachLocal(
        @CurrentUser user: User,
        @RequestBody body: AttachLocalRequest,
    ): AuthProvidersResponse {
        authService.attachLocal(user, body.localId, body.password)
        return AuthProvidersResponse(user.authProviders)
    }

    // 변경 시 기존 세션이 모두 폐기되므로 새 토큰을 돌려준다
    @PatchMapping("/me/password")
    fun changePassword(
        @CurrentUser user: User,
        @RequestBody body: ChangePasswordRequest,
    ): ChangePasswordResponse {
        val tokens = authService.changePassword(user, body.currentPassword, body.newPassword)
        return ChangePasswordResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
    }

    @PostMapping("/me/social/{provider}")
    fun attachSocial(
        @CurrentUser user: User,
        @PathVariable provider: String,
        @RequestBody body: SocialTokenRequest,
    ): AuthProvidersResponse {
        authService.attachSocial(user, parseSocialProvider(provider), body.token)
        return AuthProvidersResponse(user.authProviders)
    }

    @DeleteMapping("/me/social/{provider}")
    fun detachSocial(
        @CurrentUser user: User,
        @PathVariable provider: String,
    ): AuthProvidersResponse {
        authService.detachSocial(user, parseSocialProvider(provider))
        return AuthProvidersResponse(user.authProviders)
    }

    private fun parseSocialProvider(value: String): AuthProvider =
        AuthProvider.from(value)?.takeIf { it != AuthProvider.LOCAL }
            ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}
