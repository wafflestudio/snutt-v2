package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.domain.auth.repository.RefreshTokenRepository
import com.wafflestudio.snutt.core.domain.user.event.UserCredentialChangedEvent
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserSocialAuthRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userSocialAuthRepository: UserSocialAuthRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val userNicknameService: UserNicknameService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun get(userId: Long): User = userRepository.findByIdOrNull(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)

    fun getAllByIds(userIds: Collection<Long>): Map<Long, User> = userRepository.findAllById(userIds.distinct()).associateBy { it.id!! }

    fun searchByEmail(email: String): List<User> = userRepository.findByEmailContainingIgnoreCaseAndActiveTrue(email)

    @Transactional
    fun updateNickname(
        user: User,
        nickname: String,
    ): User {
        user.nickname = userNicknameService.appendNewTag(nickname)
        return conflictAs(ErrorType.DUPLICATE_NICKNAME) { userRepository.save(user) }
    }

    @Transactional
    fun deactivate(user: User) {
        user.active = false
        refreshTokenRepository.deleteAllByUserId(user.id!!)
        userSocialAuthRepository.deleteByUserId(user.id!!)
        userRepository.save(user)
        eventPublisher.publishEvent(UserCredentialChangedEvent(user.id!!))
    }
}
