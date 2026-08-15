package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.repository.UserSessionRepository
import com.wafflestudio.snutt.core.domain.user.event.UserCredentialChangedEvent
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userSessionRepository: UserSessionRepository,
    private val userNicknameService: UserNicknameService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun getByExternalId(externalId: String): User =
        userRepository.findByExternalIdAndActiveTrue(externalId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)

    fun get(userId: Long): User = userRepository.findByIdOrNull(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)

    fun getExternalIds(userIds: Collection<Long>): Map<Long, String> =
        userRepository.findAllById(userIds.distinct()).associate { it.id!! to it.externalId }

    fun getAllByIds(userIds: Collection<Long>): Map<Long, User> = userRepository.findAllById(userIds.distinct()).associateBy { it.id!! }

    fun searchByEmail(email: String): List<User> = userRepository.findByEmailContainingIgnoreCaseAndActiveTrue(email)

    @Transactional
    fun updateNickname(
        user: User,
        nickname: String,
    ): User {
        user.nickname = userNicknameService.appendNewTag(nickname)
        return userRepository.save(user)
    }

    @Transactional
    fun deactivate(user: User) {
        user.active = false
        userSessionRepository.revokeAllByUserId(user.id!!)
        userRepository.save(user)
        eventPublisher.publishEvent(UserCredentialChangedEvent(user.id!!))
    }

    @Transactional
    fun updateNotificationCheckedAt(user: User) {
        user.notificationCheckedAt = Instant.now()
        userRepository.save(user)
    }
}
