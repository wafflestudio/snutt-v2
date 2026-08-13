package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.EmailVerificationService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// v1 UserDto — camelCase, 시각은 LocalDateTime(ISO), nickname.tag는 문자열
data class LegacyUserDto(
    val id: String,
    val isAdmin: Boolean,
    val regDate: java.time.LocalDateTime,
    val notificationCheckedAt: java.time.LocalDateTime,
    val email: String?,
    val localId: String?,
    val fbName: String?,
    val nickname: LegacyNicknameDto,
)

// v1 UserLegacyDto (GET /v1/user/info) — 일부 필드만 snake_case
data class LegacyUserInfoDto(
    val isAdmin: Boolean,
    val regDate: java.time.ZonedDateTime,
    val notificationCheckedAt: java.time.ZonedDateTime,
    val email: String?,
    @com.fasterxml.jackson.annotation.JsonProperty("local_id")
    val localId: String?,
    @com.fasterxml.jackson.annotation.JsonProperty("fb_name")
    val fbName: String?,
)

data class LegacyNicknameDto(
    val nickname: String,
    val tag: String?,
)

data class LegacyUpdateUserRequest(
    val nickname: String? = null,
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
class V1CompatUsersController(
    private val userService: com.wafflestudio.snutt.core.domain.user.service.UserService,
) {
    @GetMapping("/me")
    fun getMe(
        @CurrentUser user: User,
    ): LegacyUserDto = user.toLegacyUserDto()

    @PatchMapping("/me")
    fun updateMe(
        @CurrentUser user: User,
        @RequestBody body: LegacyUpdateUserRequest,
    ): LegacyUserDto {
        val nickname = body.nickname?.trim().orEmpty()
        // v1은 빈 값이거나 기존과 같으면 갱신하지 않는다
        if (nickname.isEmpty() || nickname == user.nicknameWithoutTag) return user.toLegacyUserDto()
        return userService.updateNickname(user, nickname).toLegacyUserDto()
    }

    // v1은 제공자별 불리언을 내려준다 (AuthProvidersCheckDto)
    @GetMapping("/me/social_providers", "/me/auth-providers")
    fun socialProviders(
        @CurrentUser user: User,
    ): Map<String, Any?> =
        mapOf(
            "local" to (user.localId != null),
            "facebook" to (user.facebookSub != null),
            "google" to (user.googleSub != null),
            "kakao" to (user.kakaoSub != null),
            "apple" to (user.appleSub != null),
        )
}

// v1 계정 관리 경로는 단수형 /v1/user 이다 (../snutt UserController)
@RestController
@RequestMapping("/v1/user", "/user")
class V1CompatUserController(
    private val userService: com.wafflestudio.snutt.core.domain.user.service.UserService,
    private val emailVerificationService: EmailVerificationService,
    private val authService: AuthService,
) {
    @GetMapping("/info")
    fun getUserInfo(
        @CurrentUser user: User,
    ): LegacyUserInfoDto = user.toLegacyUserInfoDto()

    @DeleteMapping("/account")
    fun deleteAccount(
        @CurrentUser user: User,
    ) {
        userService.deactivate(user)
    }

    @PostMapping("/email/verification")
    fun sendVerificationEmail(
        @CurrentUser user: User,
        @RequestBody body: SendVerificationEmailRequest,
    ) {
        emailVerificationService.sendVerificationCode(user, body.email)
    }

    @GetMapping("/email/verification")
    fun getEmailVerification(
        @CurrentUser user: User,
    ): Map<String, Any?> = mapOf("isEmailVerified" to user.isEmailVerified)

    @DeleteMapping("/email/verification")
    fun resetEmailVerification(
        @CurrentUser user: User,
    ): Map<String, Any?> {
        emailVerificationService.resetEmailVerification(user)
        return mapOf("isEmailVerified" to false)
    }

    @PostMapping("/email/verification/code")
    fun confirmEmailVerification(
        @CurrentUser user: User,
        @RequestBody body: VerificationCodeRequest,
    ): Map<String, Any?> {
        emailVerificationService.verifyEmail(user, body.code)
        return mapOf("isEmailVerified" to true)
    }

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

    @PostMapping("/facebook", "/google", "/kakao", "/apple")
    fun attachSocial(
        @CurrentUser user: User,
        @RequestBody body: LegacySocialTokenRequest,
        request: jakarta.servlet.http.HttpServletRequest,
    ): Map<String, Any?> = mapOf("token" to authService.attachSocial(user, request.socialProvider(), body.token))

    @DeleteMapping("/facebook", "/google", "/kakao", "/apple")
    fun detachSocial(
        @CurrentUser user: User,
        request: jakarta.servlet.http.HttpServletRequest,
    ): Map<String, Any?> = mapOf("token" to authService.detachSocial(user, request.socialProvider()))

    // 경로 끝 세그먼트가 곧 소셜 제공자다
    private fun jakarta.servlet.http.HttpServletRequest.socialProvider(): AuthProvider =
        AuthProvider.from(requestURI.substringAfterLast('/'))?.takeIf { it != AuthProvider.LOCAL }
            ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}

private val KST: java.time.ZoneId = java.time.ZoneId.of("Asia/Seoul")

internal fun User.toLegacyUserDto() =
    LegacyUserDto(
        id = externalId,
        isAdmin = isAdmin,
        regDate = checkNotNull(createdAt).atZone(KST).toLocalDateTime(),
        notificationCheckedAt = notificationCheckedAt.atZone(KST).toLocalDateTime(),
        email = email,
        localId = localId,
        fbName = facebookName,
        nickname = LegacyNicknameDto(nickname = nicknameWithoutTag, tag = nicknameTag?.toString()),
    )

internal fun User.toLegacyUserInfoDto() =
    LegacyUserInfoDto(
        isAdmin = isAdmin,
        regDate = checkNotNull(createdAt).atZone(KST),
        notificationCheckedAt = notificationCheckedAt.atZone(KST),
        email = email,
        localId = localId,
        fbName = facebookName,
    )

data class SendVerificationEmailRequest(
    @com.fasterxml.jackson.annotation.JsonAlias("user_email")
    val email: String,
)

data class VerificationCodeRequest(
    val code: String,
)
