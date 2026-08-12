package com.wafflestudio.snutt.api.v2.user

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.UserService
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
}
