package com.wafflestudio.snutt.api.v2.user

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.EmailVerificationService
import com.wafflestudio.snutt.core.domain.user.service.UserService
import jakarta.validation.Valid
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
    val id: Long,
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

private fun User.toResponse(authProviders: List<AuthProvider>) =
    UserResponse(
        id = id!!,
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
        @CurrentUserId userId: Long,
    ): UserResponse = userService.get(userId).toResponse(authService.getAuthProviders(userId))

    @PatchMapping("/me")
    fun updateMe(
        @CurrentUserId userId: Long,
        @Valid @RequestBody request: UpdateUserRequest,
    ): UserResponse = userService.updateNickname(userId, request.nickname).toResponse(authService.getAuthProviders(userId))

    @DeleteMapping("/me")
    fun deleteMe(
        @CurrentUserId userId: Long,
    ) {
        userService.deactivate(userId)
    }

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
        @CurrentUserId userId: Long,
        @RequestBody body: SendVerificationEmailRequest,
    ) {
        emailVerificationService.sendVerificationCode(userId, body.email)
    }

    @GetMapping("/me/email/verification")
    fun getEmailVerification(
        @CurrentUserId userId: Long,
    ): EmailVerificationResultResponse = EmailVerificationResultResponse(userService.get(userId).isEmailVerified)

    @DeleteMapping("/me/email/verification")
    fun resetEmailVerification(
        @CurrentUserId userId: Long,
    ): EmailVerificationResultResponse {
        emailVerificationService.resetEmailVerification(userId)
        return EmailVerificationResultResponse(false)
    }

    @PostMapping("/me/email/verification/code")
    fun confirmEmailVerification(
        @CurrentUserId userId: Long,
        @RequestBody body: VerificationCodeRequest,
    ): EmailVerificationResultResponse {
        emailVerificationService.verifyEmail(userId, body.code)
        return EmailVerificationResultResponse(true)
    }

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

    data class AuthProvidersResponse(
        val authProviders: List<AuthProvider>,
    )

    @PostMapping("/me/password")
    fun attachLocal(
        @CurrentUserId userId: Long,
        @RequestBody body: AttachLocalRequest,
    ): AuthProvidersResponse {
        authService.attachLocal(userId, body.localId, body.password)
        return AuthProvidersResponse(authService.getAuthProviders(userId))
    }

    @PatchMapping("/me/password")
    fun changePassword(
        @CurrentUserId userId: Long,
        @RequestBody body: ChangePasswordRequest,
    ): ChangePasswordResponse {
        val tokens = authService.changePassword(userId, body.currentPassword, body.newPassword)
        return ChangePasswordResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
    }

    @PostMapping("/me/social/{provider}")
    fun attachSocial(
        @CurrentUserId userId: Long,
        @PathVariable provider: String,
        @RequestBody body: SocialTokenRequest,
    ): AuthProvidersResponse {
        authService.attachSocial(userId, parseSocialProvider(provider), body.token)
        return AuthProvidersResponse(authService.getAuthProviders(userId))
    }

    @DeleteMapping("/me/social/{provider}")
    fun detachSocial(
        @CurrentUserId userId: Long,
        @PathVariable provider: String,
    ): AuthProvidersResponse {
        authService.detachSocial(userId, parseSocialProvider(provider))
        return AuthProvidersResponse(authService.getAuthProviders(userId))
    }

    private fun parseSocialProvider(value: String): AuthProvider =
        AuthProvider.from(value)?.takeIf { it != AuthProvider.LOCAL }
            ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}
