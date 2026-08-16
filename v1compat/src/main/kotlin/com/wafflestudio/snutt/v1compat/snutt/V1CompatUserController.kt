package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.EmailVerificationService
import com.wafflestudio.snutt.core.domain.user.service.UserService
import com.wafflestudio.snutt.v1compat.auth.LegacyTokenService
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.snutt.dto.KST
import com.wafflestudio.snutt.v1compat.snutt.dto.toLegacyLocalDateTime
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.ZonedDateTime

data class LegacyUserDto(
    val id: String,
    val isAdmin: Boolean,
    val regDate: LocalDateTime,
    val notificationCheckedAt: LocalDateTime,
    val email: String?,
    val localId: String?,
    val fbName: String?,
    val nickname: LegacyNicknameDto,
)

data class LegacyUserInfoDto(
    val isAdmin: Boolean,
    val regDate: ZonedDateTime,
    val notificationCheckedAt: ZonedDateTime,
    val email: String?,
    @param:JsonProperty("local_id")
    val localId: String?,
    @param:JsonProperty("fb_name")
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

data class LegacySocialProvidersResponse(
    val local: Boolean,
    val facebook: Boolean,
    val google: Boolean,
    val kakao: Boolean,
    val apple: Boolean,
)

data class LegacyEmailVerificationResponse(
    val isEmailVerified: Boolean,
)

data class LegacyTokenResponse(
    val token: String,
)

@RestController
@RequestMapping("/v1/users")
class V1CompatUsersController(
    private val userService: UserService,
) {
    @GetMapping("/me")
    fun getMe(
        @V1CurrentUser user: User,
    ): LegacyUserDto = user.toLegacyUserDto()

    @PatchMapping("/me")
    fun updateMe(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyUpdateUserRequest,
    ): LegacyUserDto {
        val nickname = body.nickname?.trim().orEmpty()
        if (nickname.isEmpty() || nickname == user.nicknameWithoutTag) return user.toLegacyUserDto()
        return userService.updateNickname(user, nickname).toLegacyUserDto()
    }

    @GetMapping("/me/social_providers", "/me/auth-providers")
    fun socialProviders(
        @V1CurrentUser user: User,
    ): LegacySocialProvidersResponse =
        LegacySocialProvidersResponse(
            local = user.localId != null,
            facebook = user.facebookSub != null,
            google = user.googleSub != null,
            kakao = user.kakaoSub != null,
            apple = user.appleSub != null,
        )
}

@RestController
@RequestMapping("/v1/user")
class V1CompatUserController(
    private val userService: UserService,
    private val emailVerificationService: EmailVerificationService,
    private val authService: AuthService,
    private val legacyTokenService: LegacyTokenService,
) {
    @GetMapping("/info")
    fun getUserInfo(
        @V1CurrentUser user: User,
    ): LegacyUserInfoDto = user.toLegacyUserInfoDto()

    @DeleteMapping("/account")
    fun deleteAccount(
        @V1CurrentUser user: User,
    ) {
        userService.deactivate(user)
    }

    @PostMapping("/email/verification")
    fun sendVerificationEmail(
        @V1CurrentUser user: User,
        @RequestBody body: SendVerificationEmailRequest,
    ) {
        emailVerificationService.sendVerificationCode(user, body.email)
    }

    @GetMapping("/email/verification")
    fun getEmailVerification(
        @V1CurrentUser user: User,
    ): LegacyEmailVerificationResponse = LegacyEmailVerificationResponse(isEmailVerified = user.isEmailVerified)

    @DeleteMapping("/email/verification")
    fun resetEmailVerification(
        @V1CurrentUser user: User,
    ): LegacyEmailVerificationResponse {
        emailVerificationService.resetEmailVerification(user)
        return LegacyEmailVerificationResponse(isEmailVerified = false)
    }

    @PostMapping("/email/verification/code")
    fun confirmEmailVerification(
        @V1CurrentUser user: User,
        @RequestBody body: VerificationCodeRequest,
    ): LegacyEmailVerificationResponse {
        emailVerificationService.verifyEmail(user, body.code)
        return LegacyEmailVerificationResponse(isEmailVerified = true)
    }

    @PostMapping("/password")
    fun attachLocal(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyAttachLocalRequest,
    ): LegacyTokenResponse {
        authService.attachLocal(user, body.id, body.password)
        return LegacyTokenResponse(token = legacyTokenService.issue(user))
    }

    @PutMapping("/password")
    fun changePassword(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyChangePasswordRequest,
    ): LegacyTokenResponse {
        authService.changePassword(user, body.currentPassword, body.newPassword)
        return LegacyTokenResponse(token = legacyTokenService.issue(user))
    }

    @PostMapping("/{provider:facebook|google|kakao|apple}")
    fun attachSocial(
        @V1CurrentUser user: User,
        @PathVariable provider: String,
        @RequestBody body: LegacySocialTokenRequest,
    ): LegacyTokenResponse {
        authService.attachSocial(user, socialProvider(provider), body.token)
        return LegacyTokenResponse(token = legacyTokenService.issue(user))
    }

    @DeleteMapping("/{provider:facebook|google|kakao|apple}")
    fun detachSocial(
        @V1CurrentUser user: User,
        @PathVariable provider: String,
    ): LegacyTokenResponse {
        authService.detachSocial(user, socialProvider(provider))
        return LegacyTokenResponse(token = legacyTokenService.issue(user))
    }

    private fun socialProvider(value: String): AuthProvider =
        AuthProvider.from(value)?.takeIf { it != AuthProvider.LOCAL }
            ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}

internal fun User.toLegacyUserDto() =
    LegacyUserDto(
        id = externalId,
        isAdmin = isAdmin,
        regDate = checkNotNull(createdAt).toLegacyLocalDateTime(),
        notificationCheckedAt = notificationCheckedAt.toLegacyLocalDateTime(),
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
    @param:JsonAlias("user_email")
    val email: String,
)

data class VerificationCodeRequest(
    val code: String,
)
