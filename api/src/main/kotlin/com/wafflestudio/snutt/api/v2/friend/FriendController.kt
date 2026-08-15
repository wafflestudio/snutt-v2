package com.wafflestudio.snutt.api.v2.friend

import com.wafflestudio.snutt.api.auth.CurrentUser
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
    val id: String,
    val userId: String,
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
        id = friend.externalId,
        userId = user.externalId,
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
        @CurrentUser user: User,
        @RequestParam state: String,
    ): List<FriendResponse> {
        val friendState =
            FriendState.entries.firstOrNull { it.name == state.uppercase() }
                ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        return friendService.getMyFriends(user.id!!, friendState).map { (friend, partner) ->
            FriendResponse(friend, partner, friend.getPartnerDisplayName(user.id!!))
        }
    }

    @PostMapping("")
    fun requestFriend(
        @CurrentUser user: User,
        @RequestBody body: FriendRequest,
    ) {
        friendService.requestFriend(user.id!!, body.nickname)
    }

    @PostMapping("/{friendId}/accept")
    fun acceptFriend(
        @CurrentUser user: User,
        @PathVariable friendId: String,
    ) {
        friendService.acceptFriend(friendId, user.id!!)
    }

    @PostMapping("/{friendId}/decline")
    fun declineFriend(
        @CurrentUser user: User,
        @PathVariable friendId: String,
    ) {
        friendService.declineFriend(friendId, user.id!!)
    }

    @PatchMapping("/{friendId}/display-name")
    fun updateFriendDisplayName(
        @CurrentUser user: User,
        @PathVariable friendId: String,
        @RequestBody body: UpdateFriendDisplayNameRequest,
    ) {
        friendService.updateFriendDisplayName(user.id!!, friendId, body.displayName)
    }

    @DeleteMapping("/{friendId}")
    fun breakFriend(
        @CurrentUser user: User,
        @PathVariable friendId: String,
    ) {
        friendService.breakFriend(friendId, user.id!!)
    }

    @GetMapping("/generate-link")
    fun generateFriendLink(
        @CurrentUser user: User,
    ): FriendRequestLinkResponse = FriendRequestLinkResponse(requestToken = friendService.generateFriendRequestLink(user.id!!))

    @PostMapping("/accept-link/{requestToken}")
    fun acceptFriendByLink(
        @CurrentUser user: User,
        @PathVariable requestToken: String,
    ): FriendResponse {
        val (friend, partner) = friendService.acceptFriendByLink(user.id!!, requestToken)
        return FriendResponse(friend, partner, friend.getPartnerDisplayName(user.id!!))
    }

    // 친구의 대표 시간표/수강편람 (v1 FriendTableController 이식)
    @GetMapping("/{friendId}/primary-table")
    fun getPrimaryTable(
        @CurrentUser user: User,
        @PathVariable friendId: String,
        @RequestParam year: Int,
        @RequestParam semester: Int,
    ): TimetableResponse {
        val friend = getAcceptedFriend(user.id!!, friendId)
        val partnerId = friend.getPartnerUserId(user.id!!)
        val timetable = timetableService.getUserPrimaryTable(partnerId, year, parseSemester(semester))
        return timetableService.getTimetableDisplay(partnerId, timetable.externalId).toResponse()
    }

    @GetMapping("/{friendId}/coursebooks", "/{friendId}/registered-course-books")
    fun getCoursebooks(
        @CurrentUser user: User,
        @PathVariable friendId: String,
    ): List<FriendCoursebookResponse> {
        val friend = getAcceptedFriend(user.id!!, friendId)
        return timetableService
            .getCoursebooksWithPrimaryTable(friend.getPartnerUserId(user.id!!))
            .map { FriendCoursebookResponse(year = it.first, semester = it.second.value) }
    }

    private fun getAcceptedFriend(
        userId: Long,
        friendExternalId: String,
    ): Friend {
        val friend = friendService.get(friendExternalId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (!friend.isAccepted || !friend.includes(userId)) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        return friend
    }

    private fun parseSemester(value: Int) = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}
