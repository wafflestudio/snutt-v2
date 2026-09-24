package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.authProvidersOf
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.device.service.DeviceService
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.model.UserSocialAuth
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserSocialAuthRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userSocialAuthRepository: UserSocialAuthRepository,
    private val userNicknameService: UserNicknameService,
    private val authService: AuthService,
    private val deviceService: DeviceService,
    private val friendRepository: FriendRepository,
) {
    fun findActive(userId: Long): User? = userRepository.findByIdAndActiveTrue(userId)

    fun get(userId: Long): User = findActive(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)

    fun getAllByIds(userIds: Collection<Long>): Map<Long, User> = userRepository.findAllById(userIds.distinct()).associateBy { it.id!! }

    fun searchByEmail(email: String): List<User> = userRepository.findByEmailContainingIgnoreCaseAndActiveTrue(email)

    @Transactional(readOnly = true)
    fun searchByEmailWithAuthInfo(email: String): List<UserAuthInfo> {
        val users = searchByEmail(email)
        val socialAuths = userSocialAuthRepository.findByUserIdIn(users.mapNotNull { it.id }).groupBy { it.userId }
        return users.map { UserAuthInfo(it, socialAuths[it.id].orEmpty()) }
    }

    @Transactional
    fun updateNickname(
        userId: Long,
        nickname: String,
    ): User {
        val user = get(userId)
        val generated = userNicknameService.generate(nickname)
        user.nickname = generated.name
        user.nicknameTag = generated.tag
        return conflictAs(ErrorType.DUPLICATE_NICKNAME) { userRepository.saveAndFlush(user) }
    }

    @Transactional
    fun deactivate(userId: Long) {
        val user = userRepository.findForUpdateByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        user.active = false
        userSocialAuthRepository.deleteByUserId(userId)
        deviceService.removeAllByUserId(userId)
        friendRepository.deleteByUserId(userId)
        authService.revokeSessions(user)
    }
}

data class UserAuthInfo(
    val user: User,
    val socialAuths: List<UserSocialAuth>,
) {
    val authProviders: List<AuthProvider> = authProvidersOf(user, socialAuths.map { it.provider })
}
