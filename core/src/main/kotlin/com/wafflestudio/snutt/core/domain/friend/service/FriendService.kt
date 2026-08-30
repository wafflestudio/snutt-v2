package com.wafflestudio.snutt.core.domain.friend.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.domain.friend.model.Friend
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

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
        toUserNickname: String,
    ) {
        val toUser =
            userRepository.findByNicknameAndActiveTrue(toUserNickname)
                ?: throw SnuttException(ErrorType.USER_NOT_FOUND_BY_NICKNAME)
        val toUserId = toUser.id!!
        if (fromUserId == toUserId) throw SnuttException(ErrorType.INVALID_FRIEND)
        if (friendRepository.findByUserPair(fromUserId, toUserId) != null) {
            throw SnuttException(ErrorType.DUPLICATE_FRIEND)
        }
        conflictAs(ErrorType.DUPLICATE_FRIEND) {
            friendRepository.save(Friend(fromUserId = fromUserId, toUserId = toUserId))
        }
        val fromUser = userRepository.findByIdAndActiveTrue(fromUserId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        notify(
            userId = toUserId,
            title = "친구 요청",
            body = "'${fromUser.nicknameWithoutTag}'님의 친구 요청을 수락하고 서로의 대표 시간표를 확인해보세요!",
        )
    }

    @Transactional
    fun acceptFriend(
        friendId: Long,
        toUserId: Long,
    ) {
        val friend = get(friendId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (friend.toUserId != toUserId || friend.isAccepted) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        friend.isAccepted = true
        val toUser = userRepository.findByIdAndActiveTrue(toUserId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        notify(
            userId = friend.fromUserId,
            title = "친구 요청 수락",
            body = "'${toUser.nicknameWithoutTag}'님과 친구가 되었어요.",
        )
    }

    @Transactional
    fun declineFriend(
        friendId: Long,
        toUserId: Long,
    ) {
        val friend = get(friendId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (friend.toUserId != toUserId || friend.isAccepted) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        friendRepository.delete(friend)
    }

    @Transactional
    fun breakFriend(
        friendId: Long,
        userId: Long,
    ) {
        val friend = get(friendId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (!friend.includes(userId) || !friend.isAccepted) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        friendRepository.delete(friend)
    }

    @Transactional
    fun updateFriendDisplayName(
        userId: Long,
        friendId: Long,
        displayName: String,
    ) {
        val friend = get(friendId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (!friend.includes(userId) || !friend.isAccepted) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        val valid = displayName.length <= DISPLAY_NAME_MAX_LENGTH && displayName.matches(friendDisplayNameRegex)
        if (!valid) throw SnuttException(ErrorType.INVALID_DISPLAY_NAME)
        friend.updatePartnerDisplayName(userId, displayName)
    }

    fun get(friendId: Long): Friend? = friendRepository.findByIdOrNull(friendId)

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
        if (fromUser.id == userId) throw SnuttException(ErrorType.INVALID_FRIEND)
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
            title = "친구 요청 수락",
            body = "'${toUser.nicknameWithoutTag}'님과 친구가 되었어요.",
        )
        return friend to fromUser
    }

    private fun notify(
        userId: Long,
        title: String,
        body: String,
    ) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    pushService.sendPushAndNotification(
                        userIds = listOf(userId),
                        title = title,
                        body = body,
                        type = NotificationType.FRIEND,
                        preferenceType = PushPreferenceType.NORMAL,
                        urlScheme = FRIEND_URL_SCHEME,
                    )
                }
            },
        )
    }
}
