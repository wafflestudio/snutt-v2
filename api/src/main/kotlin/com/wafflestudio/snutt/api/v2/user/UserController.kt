package com.wafflestudio.snutt.api.v2.user

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.EmailVerificationService
import com.wafflestudio.snutt.core.domain.user.service.UserService
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
}
