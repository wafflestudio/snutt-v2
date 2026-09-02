package com.wafflestudio.snutt.api.v2.friend

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.api.v2.timetable.TimetableResponse
import com.wafflestudio.snutt.api.v2.timetable.toResponse
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.friend.model.Friend
import com.wafflestudio.snutt.core.domain.friend.service.FriendService
import com.wafflestudio.snutt.core.domain.friend.service.FriendState
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class FriendResponse(
    val id: Long,
    val userId: Long,
    val displayName: String?,
    val nickname: String,
    val nicknameTag: Int?,
    val createdAt: Long,
)

data class FriendRequest(
    @field:NotBlank val nickname: String,
)

data class UpdateFriendDisplayNameRequest(
    @field:NotBlank
    @field:Size(max = 10)
    val displayName: String,
)

data class FriendRequestLinkResponse(
    val requestToken: String,
)

data class FriendCoursebookResponse(
    val year: Int,
    val semester: Int,
)

private fun FriendResponse(
    friend: Friend,
    user: User,
    displayName: String?,
): FriendResponse =
    FriendResponse(
        id = friend.id!!,
        userId = user.id!!,
        displayName = displayName,
        nickname = user.nicknameWithoutTag,
        nicknameTag = user.nicknameTag,
        createdAt = checkNotNull(friend.createdAt).toEpochMilli(),
    )

@RestController
@RequestMapping("/v2/friends")
class FriendController(
    private val friendService: FriendService,
    private val timetableService: TimetableService,
) {
    @GetMapping("")
    fun getFriends(
        @CurrentUserId userId: Long,
        @RequestParam state: String,
    ): List<FriendResponse> {
        val friendState =
            FriendState.entries.firstOrNull { it.name == state.uppercase() }
                ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        return friendService.getMyFriends(userId, friendState).map { (friend, partner) ->
            FriendResponse(friend, partner, friend.getPartnerDisplayName(userId))
        }
    }

    @PostMapping("")
    fun requestFriend(
        @CurrentUserId userId: Long,
        @Valid @RequestBody body: FriendRequest,
    ) {
        friendService.requestFriend(userId, body.nickname)
    }

    @PostMapping("/{friendId}/accept")
    fun acceptFriend(
        @CurrentUserId userId: Long,
        @PathVariable friendId: Long,
    ) {
        friendService.acceptFriend(friendId, userId)
    }

    @PostMapping("/{friendId}/decline")
    fun declineFriend(
        @CurrentUserId userId: Long,
        @PathVariable friendId: Long,
    ) {
        friendService.declineFriend(friendId, userId)
    }

    @PatchMapping("/{friendId}/display-name")
    fun updateFriendDisplayName(
        @CurrentUserId userId: Long,
        @PathVariable friendId: Long,
        @Valid @RequestBody body: UpdateFriendDisplayNameRequest,
    ) {
        friendService.updateFriendDisplayName(userId, friendId, body.displayName)
    }

    @DeleteMapping("/{friendId}")
    fun breakFriend(
        @CurrentUserId userId: Long,
        @PathVariable friendId: Long,
    ) {
        friendService.breakFriend(friendId, userId)
    }

    @GetMapping("/generate-link")
    fun generateFriendLink(
        @CurrentUserId userId: Long,
    ): FriendRequestLinkResponse = FriendRequestLinkResponse(requestToken = friendService.generateFriendRequestLink(userId))

    @PostMapping("/accept-link/{requestToken}")
    fun acceptFriendByLink(
        @CurrentUserId userId: Long,
        @PathVariable requestToken: String,
    ): FriendResponse {
        val (friend, partner) = friendService.acceptFriendByLink(userId, requestToken)
        return FriendResponse(friend, partner, friend.getPartnerDisplayName(userId))
    }

    @GetMapping("/{friendId}/primary-table")
    fun getPrimaryTable(
        @CurrentUserId userId: Long,
        @PathVariable friendId: Long,
        @RequestParam year: Int,
        @RequestParam semester: Int,
    ): TimetableResponse {
        val friend = getAcceptedFriend(userId, friendId)
        val partnerId = friend.getPartnerUserId(userId)
        val timetable = timetableService.getUserPrimaryTable(partnerId, year, parseSemester(semester))
        return timetableService.getTimetableDisplay(partnerId, timetable.id!!).toResponse()
    }

    @GetMapping("/{friendId}/coursebooks")
    fun getCoursebooks(
        @CurrentUserId userId: Long,
        @PathVariable friendId: Long,
    ): List<FriendCoursebookResponse> {
        val friend = getAcceptedFriend(userId, friendId)
        return timetableService
            .getCoursebooksWithPrimaryTable(friend.getPartnerUserId(userId))
            .map { FriendCoursebookResponse(year = it.first, semester = it.second.value) }
    }

    private fun getAcceptedFriend(
        userId: Long,
        friendId: Long,
    ): Friend {
        val friend = friendService.get(friendId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (!friend.isAccepted || !friend.includes(userId)) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        return friend
    }

    private fun parseSemester(value: Int) = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}
