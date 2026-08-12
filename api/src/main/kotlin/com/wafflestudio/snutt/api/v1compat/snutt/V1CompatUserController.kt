package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.EmailVerificationService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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

@RestController
@RequestMapping("/v1/users", "/users")
class V1CompatUserController(
    private val userService: com.wafflestudio.snutt.core.domain.user.service.UserService,
    private val emailVerificationService: EmailVerificationService,
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
