package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.EmailVerificationService
import com.wafflestudio.snutt.core.domain.user.service.PasswordResetService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// v1 UserDto 형태 (../snutt/users/dto/UserDto.kt)
data class LegacyUserDto(
    val id: String,
    val isAdmin: Boolean,
    val regDate: Long,
    val notificationCheckedAt: Long,
    val email: String?,
    @com.fasterxml.jackson.annotation.JsonProperty("local_id")
    val localId: String?,
    @com.fasterxml.jackson.annotation.JsonProperty("fb_name")
    val fbName: String?,
    val nickname: LegacyNicknameDto,
)

data class LegacyNicknameDto(
    val nickname: String,
    val tag: Int?,
)

data class LegacyUpdateUserRequest(
    val nickname: String,
)

data class LegacyAttachLocalRequest(
    val id: String,
    val password: String,
)

data class LegacyChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

data class LegacySocialTokenRequest(
    val token: String,
)

@RestController
@RequestMapping("/v1/users", "/users")
class V1CompatUserController(
    private val userService: com.wafflestudio.snutt.core.domain.user.service.UserService,
    private val emailVerificationService: EmailVerificationService,
    private val authService: AuthService,
    private val passwordResetService: PasswordResetService,
) {
    @GetMapping("/me")
    fun getMe(
        @CurrentUser user: User,
    ): LegacyUserDto = user.toLegacy()

    @PatchMapping("/me")
    fun updateMe(
        @CurrentUser user: User,
        @RequestBody body: LegacyUpdateUserRequest,
    ): LegacyUserDto = userService.updateNickname(user, body.nickname).toLegacy()

    // v1 이메일 인증 (UserController 이식)
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
    ): Map<String, Any?> = mapOf("isEmailVerified" to user.isEmailVerified)

    @DeleteMapping("/me/email/verification")
    fun resetEmailVerification(
        @CurrentUser user: User,
    ): Map<String, Any?> {
        emailVerificationService.resetEmailVerification(user)
        return mapOf("isEmailVerified" to false)
    }

    @PostMapping("/me/email/verification/code")
    fun confirmEmailVerification(
        @CurrentUser user: User,
        @RequestBody body: VerificationCodeRequest,
    ): Map<String, Any?> {
        emailVerificationService.verifyEmail(user, body.code)
        return mapOf("isEmailVerified" to true)
    }

    // v1 계정 관리 (UserController 이식): 소셜/로컬 계정 연동·해제, 비밀번호 변경
    @PostMapping("/password")
    fun attachLocal(
        @CurrentUser user: User,
        @RequestBody body: LegacyAttachLocalRequest,
    ): Map<String, Any?> = mapOf("token" to authService.attachLocal(user, body.id, body.password))

    @PutMapping("/password")
    fun changePassword(
        @CurrentUser user: User,
        @RequestBody body: LegacyChangePasswordRequest,
    ): Map<String, Any?> = mapOf("token" to authService.changePassword(user, body.currentPassword, body.newPassword))

    @PostMapping("/{provider}", params = ["token"])
    fun attachSocial(
        @CurrentUser user: User,
        @PathVariable provider: String,
        @RequestParam token: String,
    ): Map<String, Any?> = mapOf("token" to authService.attachSocial(user, parseSocialProvider(provider), token))

    @DeleteMapping("/{provider}")
    fun detachSocial(
        @CurrentUser user: User,
        @PathVariable provider: String,
    ): Map<String, Any?> = mapOf("token" to authService.detachSocial(user, parseSocialProvider(provider)))

    @GetMapping("/me/social_providers", "/me/auth-providers")
    fun socialProviders(
        @CurrentUser user: User,
    ): Map<String, Any?> = mapOf("authProviders" to user.authProviders.map { it.korName })

    private fun parseSocialProvider(value: String): AuthProvider =
        AuthProvider.from(value)?.takeIf { it != AuthProvider.LOCAL }
            ?: throw SnuttException(ErrorType.INVALID_PARAMETER)

    private fun User.toLegacy() =
        LegacyUserDto(
            id = externalId,
            isAdmin = isAdmin,
            regDate = checkNotNull(createdAt).toEpochMilli(),
            notificationCheckedAt = notificationCheckedAt.toEpochMilli(),
            email = email,
            localId = localId,
            fbName = facebookName,
            nickname = LegacyNicknameDto(nickname = nicknameWithoutTag, tag = nicknameTag),
        )
}

data class SendVerificationEmailRequest(
    val email: String,
)

data class VerificationCodeRequest(
    val code: String,
)
