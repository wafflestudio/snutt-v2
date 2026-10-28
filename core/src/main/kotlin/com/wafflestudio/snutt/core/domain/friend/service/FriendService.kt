package com.wafflestudio.snutt.core.domain.friend.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.domain.friend.model.Friend
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.notification.model.NotificationTemplate
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.user.model.Nickname
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class FriendState {
    ACTIVE,
    REQUESTING,
    REQUESTED,
}

@Service
class FriendService(
    private val friendRepository: FriendRepository,
    private val userRepository: UserRepository,
    private val friendLinkTokenProvider: FriendLinkTokenProvider,
    private val pushService: PushService,
) {
    companion object {
        private val friendDisplayNameRegex = "^[a-zA-Z가-힣0-9 ]+$".toRegex()
        private const val DISPLAY_NAME_MAX_LENGTH = 10
        private const val FRIEND_URL_SCHEME = "snutt://friends?openDrawer=true"
    }

    fun getMyFriends(
        myUserId: Long,
        state: FriendState,
    ): List<Pair<Friend, User>> {
        val friends =
            when (state) {
                FriendState.ACTIVE -> friendRepository.findActiveByUserId(myUserId)
                FriendState.REQUESTING -> friendRepository.findByFromUserIdAndIsAcceptedFalseOrderByCreatedAtDesc(myUserId)
                FriendState.REQUESTED -> friendRepository.findByToUserIdAndIsAcceptedFalseOrderByCreatedAtDesc(myUserId)
            }
        if (friends.isEmpty()) return emptyList()
        val partnerIds = friends.map { it.getPartnerUserId(myUserId) }
        val users = userRepository.findAllByIdInAndActiveTrue(partnerIds).associateBy { it.id!! }
        return friends.mapNotNull { friend -> users[friend.getPartnerUserId(myUserId)]?.let { friend to it } }
    }

    @Transactional
    fun requestFriend(
        fromUserId: Long,
        toUserNickname: Nickname,
    ): Pair<Friend, User> {
        val toUser =
            userRepository.findByNicknameAndNicknameTagAndActiveTrue(toUserNickname.name, toUserNickname.tag)
                ?: throw SnuttException(ErrorType.USER_NOT_FOUND_BY_NICKNAME)
        val toUserId = toUser.id!!
        if (fromUserId == toUserId) throw SnuttException(ErrorType.SELF_FRIEND_REQUEST)
        if (friendRepository.findByUserPair(fromUserId, toUserId) != null) {
            throw SnuttException(ErrorType.DUPLICATE_FRIEND)
        }
        val friend =
            conflictAs(ErrorType.DUPLICATE_FRIEND) {
                friendRepository.save(Friend(fromUserId = fromUserId, toUserId = toUserId))
            }
        val fromUser = userRepository.findByIdAndActiveTrue(fromUserId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        notify(
            userId = toUserId,
            template = NotificationTemplate.FRIEND_REQUEST,
            nickname = fromUser.nickname,
        )
        return friend to toUser
    }

    @Transactional
    fun acceptFriend(
        friendId: Long,
        toUserId: Long,
    ): Pair<Friend, User> {
        val friend = getRequestedTo(toUserId, friendId)
        friend.isAccepted = true
        val toUser = userRepository.findByIdAndActiveTrue(toUserId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val fromUser = userRepository.findByIdAndActiveTrue(friend.fromUserId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        notify(
            userId = friend.fromUserId,
            template = NotificationTemplate.FRIEND_REQUEST_ACCEPTED,
            nickname = toUser.nickname,
        )
        return friend to fromUser
    }

    @Transactional
    fun declineFriend(
        friendId: Long,
        toUserId: Long,
    ) {
        val friend = getRequestedTo(toUserId, friendId)
        friendRepository.delete(friend)
    }

    @Transactional
    fun breakFriend(
        friendId: Long,
        userId: Long,
    ) {
        val friend = getAccepted(userId, friendId)
        friendRepository.delete(friend)
    }

    @Transactional
    fun updateFriendDisplayName(
        userId: Long,
        friendId: Long,
        displayName: String,
    ): Pair<Friend, User> {
        val friend = getAccepted(userId, friendId)
        val valid = displayName.length <= DISPLAY_NAME_MAX_LENGTH && displayName.matches(friendDisplayNameRegex)
        if (!valid) throw SnuttException(ErrorType.INVALID_DISPLAY_NAME)
        friend.updatePartnerDisplayName(userId, displayName)
        val partner =
            userRepository.findByIdAndActiveTrue(friend.getPartnerUserId(userId)) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        return friend to partner
    }

    fun getAccepted(
        userId: Long,
        friendId: Long,
    ): Friend =
        friendRepository.findByIdOrNull(friendId)?.takeIf { it.isAccepted && it.includes(userId) }
            ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)

    private fun getRequestedTo(
        toUserId: Long,
        friendId: Long,
    ): Friend =
        friendRepository.findByIdOrNull(friendId)?.takeIf { !it.isAccepted && it.toUserId == toUserId }
            ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)

    fun generateFriendRequestLink(userId: Long): String = friendLinkTokenProvider.issue(userId)

    @Transactional
    fun acceptFriendByLink(
        userId: Long,
        requestToken: String,
    ): Pair<Friend, User> {
        val fromUserId =
            friendLinkTokenProvider.parse(requestToken) ?: throw SnuttException(ErrorType.FRIEND_LINK_NOT_FOUND)
        val fromUser =
            userRepository.findByIdAndActiveTrue(fromUserId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        if (fromUser.id == userId) throw SnuttException(ErrorType.SELF_FRIEND_REQUEST)
        if (friendRepository.findByUserPair(fromUserId, userId) != null) {
            throw SnuttException(ErrorType.DUPLICATE_FRIEND)
        }
        val friend =
            conflictAs(ErrorType.DUPLICATE_FRIEND) {
                friendRepository.save(Friend(fromUserId = fromUserId, toUserId = userId, isAccepted = true))
            }
        val toUser = userRepository.findByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        notify(
            userId = fromUserId,
            template = NotificationTemplate.FRIEND_REQUEST_ACCEPTED,
            nickname = toUser.nickname,
        )
        return friend to fromUser
    }

    private fun notify(
        userId: Long,
        template: NotificationTemplate,
        nickname: String,
    ) {
        pushService.sendPushAndNotification(
            userIds = listOf(userId),
            message = template.render(nickname).copy(urlScheme = FRIEND_URL_SCHEME),
            type = NotificationType.FRIEND,
            preferenceType = PushPreferenceType.NORMAL,
        )
    }
}
