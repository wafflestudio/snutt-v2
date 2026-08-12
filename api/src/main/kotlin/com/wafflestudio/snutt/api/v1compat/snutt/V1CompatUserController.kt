package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
