package com.wafflestudio.snutt.core.domain.friend.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.friend.model.Friend
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

enum class FriendState {
    ACTIVE,
    REQUESTING, // 내가 보낸 요청
    REQUESTED, // 받은 요청
}

// v1 시맨틱 이식 (../snutt/core/src/main/kotlin/friend/service/FriendService.kt).
// 친구 요청/수락 푸시는 M7 FCM 클라이언트와 함께 연동한다
@Service
class FriendService(
    private val friendRepository: FriendRepository,
    private val userRepository: UserRepository,
    private val redisTemplate: StringRedisTemplate,
) {
    companion object {
        private val secureRandom = SecureRandom()
        private val friendDisplayNameRegex = "^[a-zA-Z가-힣0-9 ]+$".toRegex()
        private const val DISPLAY_NAME_MAX_LENGTH = 10
        private const val FRIEND_LINK_REDIS_PREFIX = "friend-link:"
        private val friendLinkTtl: Duration = Duration.ofDays(14)
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
        val users = userRepository.findAllById(partnerIds).associateBy { it.id!! }
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
        if (friendRepository.findByFromUserIdAndToUserId(fromUserId, toUserId) != null) {
            throw SnuttException(ErrorType.DUPLICATE_FRIEND)
        }
        friendRepository.save(Friend(fromUserId = fromUserId, toUserId = toUserId))
    }

    @Transactional
    fun acceptFriend(
        friendExternalId: String,
        toUserId: Long,
    ) {
        val friend = get(friendExternalId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (friend.toUserId != toUserId || friend.isAccepted) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        friend.isAccepted = true
    }

    @Transactional
    fun declineFriend(
        friendExternalId: String,
        toUserId: Long,
    ) {
        val friend = get(friendExternalId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (friend.toUserId != toUserId || friend.isAccepted) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        friendRepository.delete(friend)
    }

    @Transactional
    fun breakFriend(
        friendExternalId: String,
        userId: Long,
    ) {
        val friend = get(friendExternalId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (!friend.includes(userId) || !friend.isAccepted) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        friendRepository.delete(friend)
    }

    @Transactional
    fun updateFriendDisplayName(
        userId: Long,
        friendExternalId: String,
        displayName: String,
    ) {
        val friend = get(friendExternalId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (!friend.includes(userId) || !friend.isAccepted) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        val valid = displayName.length <= DISPLAY_NAME_MAX_LENGTH && displayName.matches(friendDisplayNameRegex)
        if (!valid) throw SnuttException(ErrorType.INVALID_DISPLAY_NAME)
        friend.updatePartnerDisplayName(userId, displayName)
    }

    fun get(friendExternalId: String): Friend? = friendRepository.findByExternalId(friendExternalId)

    // 14일 TTL 링크 토큰 (v1 Redis 시맨틱 그대로)
    fun generateFriendRequestLink(userId: Long): String {
        val bytes = ByteArray(8)
        var token: String
        do {
            secureRandom.nextBytes(bytes)
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } while (redisTemplate.hasKey(FRIEND_LINK_REDIS_PREFIX + token))
        redisTemplate.opsForValue().set(FRIEND_LINK_REDIS_PREFIX + token, userId.toString(), friendLinkTtl)
        return token
    }

    @Transactional
    fun acceptFriendByLink(
        userId: Long,
        requestToken: String,
    ): Pair<Friend, User> {
        val fromUserId =
            redisTemplate.opsForValue().get(FRIEND_LINK_REDIS_PREFIX + requestToken)?.toLongOrNull()
                ?: throw SnuttException(ErrorType.FRIEND_LINK_NOT_FOUND)
        val fromUser =
            userRepository.findByIdAndActiveTrue(fromUserId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        if (fromUser.id == userId) throw SnuttException(ErrorType.INVALID_FRIEND)
        if (friendRepository.findByFromUserIdAndToUserId(fromUserId, userId) != null) {
            throw SnuttException(ErrorType.DUPLICATE_FRIEND)
        }
        val friend =
            friendRepository.save(
                Friend(
                    fromUserId = fromUserId,
                    toUserId = userId,
                    isAccepted = true,
                ),
            )
        return friend to fromUser
    }
}
