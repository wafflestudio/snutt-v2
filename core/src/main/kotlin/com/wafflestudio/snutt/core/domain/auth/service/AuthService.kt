package com.wafflestudio.snutt.core.domain.auth.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.common.util.PasswordPolicy
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import com.wafflestudio.snutt.core.domain.user.event.UserCredentialChangedEvent
import com.wafflestudio.snutt.core.domain.user.event.UserRegisteredEvent
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.model.UserSocialAuth
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserSocialAuthRepository
import com.wafflestudio.snutt.core.domain.user.service.UserNicknameService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val userSocialAuthRepository: UserSocialAuthRepository,
    private val accessTokenService: AccessTokenService,
    private val userNicknameService: UserNicknameService,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: ApplicationEventPublisher,
    oauth2Clients: Map<String, OAuth2Client>,
) {
    private val oauth2Clients = oauth2Clients.mapKeys { AuthProvider.valueOf(it.key) }

    companion object {
        private val emailRegex = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".toRegex()
    }

    @Transactional
    fun registerLocal(
        localId: String,
        password: String,
        email: String?,
    ): User {
        if (!localId.matches(PasswordPolicy.localIdRegex)) throw SnuttException(ErrorType.INVALID_LOCAL_ID)
        if (!PasswordPolicy.isValidPassword(password)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        val normalizedEmail =
            email?.trim()?.also { if (!it.matches(emailRegex)) throw SnuttException(ErrorType.INVALID_EMAIL) }
        if (userRepository.existsByLocalIdAndActiveTrue(localId)) throw SnuttException(ErrorType.DUPLICATE_LOCAL_ID)

        val user =
            User(
                email = normalizedEmail,
                nickname = userNicknameService.generateUniqueRandomNickname(),
                localId = localId,
                localPw = passwordEncoder.encode(password),
            )
        save(user, ErrorType.DUPLICATE_LOCAL_ID)
        eventPublisher.publishEvent(UserRegisteredEvent(user.id!!))
        return user
    }

    @Transactional
    fun loginLocal(
        localId: String,
        password: String,
    ): User {
        val user = userRepository.findByLocalIdAndActiveTrue(localId) ?: throw SnuttException(ErrorType.WRONG_LOCAL_ID)
        if (!passwordEncoder.matches(password, user.localPw)) throw SnuttException(ErrorType.WRONG_PASSWORD)
        user.lastLoginAt = Instant.now()
        return user
    }

    @Transactional
    fun loginSocial(
        provider: AuthProvider,
        token: String,
    ): User {
        val response = fetchSocialUser(provider, token)
        val user = findBySocialResponse(provider, response) ?: createSocialUser(provider, response)
        user.lastLoginAt = Instant.now()
        return user
    }

    @Transactional(readOnly = true)
    fun authenticate(payload: AccessTokenPayload): User =
        userRepository.findByIdAndActiveTrue(payload.userId) ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN)

    fun issueToken(user: User): String = accessTokenService.issue(AccessTokenPayload(userId = user.id!!))

    @Transactional
    fun attachLocal(
        user: User,
        localId: String,
        password: String,
    ) {
        if (user.localId != null) throw SnuttException(ErrorType.ALREADY_LOCAL_ACCOUNT)
        if (!localId.matches(PasswordPolicy.localIdRegex)) throw SnuttException(ErrorType.INVALID_LOCAL_ID)
        if (!PasswordPolicy.isValidPassword(password)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        if (userRepository.existsByLocalIdAndActiveTrue(localId)) throw SnuttException(ErrorType.DUPLICATE_LOCAL_ID)
        user.localId = localId
        user.localPw = passwordEncoder.encode(password)
        save(user, ErrorType.DUPLICATE_LOCAL_ID)
        publishCredentialChanged(user)
    }

    @Transactional
    fun attachSocial(
        user: User,
        provider: AuthProvider,
        token: String,
    ) {
        val response = fetchSocialUser(provider, token)
        if (response.email != null) {
            val presentUser = userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(response.email)
            if (presentUser != null && presentUser.id != user.id) throw SnuttException(ErrorType.DUPLICATE_EMAIL)
        }
        if (userSocialAuthRepository.findByUserIdAndProvider(user.id!!, provider) != null) {
            throw SnuttException(ErrorType.ALREADY_SOCIAL_ACCOUNT)
        }
        if (existsBySocialId(provider, response.socialId)) throw SnuttException(ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
        insertSocialAuth(user.id!!, provider, response)
        publishCredentialChanged(user)
    }

    @Transactional
    fun detachSocial(
        user: User,
        provider: AuthProvider,
    ) {
        val socialProviders = userSocialAuthRepository.findByUserId(user.id!!).map { it.provider }
        if (provider !in socialProviders) throw SnuttException(ErrorType.SOCIAL_PROVIDER_NOT_ATTACHED)
        if (socialProviders.size + (if (user.localId != null) 1 else 0) == 1) {
            throw SnuttException(ErrorType.CANNOT_REMOVE_LAST_AUTH_PROVIDER)
        }
        userSocialAuthRepository.deleteByUserIdAndProvider(user.id!!, provider)
        publishCredentialChanged(user)
    }

    @Transactional(readOnly = true)
    fun getAuthProviders(user: User): List<AuthProvider> =
        buildList {
            if (user.localId != null) add(AuthProvider.LOCAL)
            userSocialAuthRepository
                .findByUserId(user.id!!)
                .sortedBy { it.provider.ordinal }
                .forEach { add(it.provider) }
        }

    @Transactional
    fun changePassword(
        user: User,
        currentPassword: String,
        newPassword: String,
    ): String {
        if (user.localPw == null) throw SnuttException(ErrorType.INVALID_LOCAL_ID)
        if (!passwordEncoder.matches(currentPassword, user.localPw)) throw SnuttException(ErrorType.WRONG_PASSWORD)
        if (!PasswordPolicy.isValidPassword(newPassword)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        user.localPw = passwordEncoder.encode(newPassword)
        userRepository.save(user)
        publishCredentialChanged(user)
        return issueToken(user)
    }

    private fun fetchSocialUser(
        provider: AuthProvider,
        token: String,
    ): OAuth2UserResponse {
        require(provider != AuthProvider.LOCAL) { "LOCAL is not a social provider" }
        val oauth2Client = checkNotNull(oauth2Clients[provider]) { "unsupported provider: $provider" }
        return oauth2Client.getMe(token) ?: throw SnuttException(ErrorType.SOCIAL_CONNECT_FAIL)
    }

    private fun findBySocialResponse(
        provider: AuthProvider,
        response: OAuth2UserResponse,
    ): User? {
        require(provider != AuthProvider.LOCAL) { "LOCAL is not a social provider" }
        userSocialAuthRepository.findActiveUserByProviderAndSub(provider, response.socialId)?.let { return it }
        if (provider != AuthProvider.APPLE) return null
        val transferSub = response.transferInfo ?: return null
        return userSocialAuthRepository.findActiveByProviderAndTransferSub(provider, transferSub)?.let { auth ->
            auth.sub = response.socialId
            auth.email = response.email
            userSocialAuthRepository.save(auth)
            userRepository.findByIdAndActiveTrue(auth.userId)
        }
    }

    private fun existsBySocialId(
        provider: AuthProvider,
        socialId: String,
    ): Boolean = userSocialAuthRepository.existsByProviderAndSub(provider, socialId)

    private fun createSocialUser(
        provider: AuthProvider,
        response: OAuth2UserResponse,
    ): User {
        if (response.email != null) {
            val present = userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(response.email)
            if (present != null) throw SnuttException(ErrorType.DUPLICATE_EMAIL)
        }
        val user =
            User(
                email = response.email,
                isEmailVerified = response.email != null && response.isEmailVerified,
                nickname = userNicknameService.generateUniqueRandomNickname(),
            )
        save(user, ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
        insertSocialAuth(user.id!!, provider, response)
        eventPublisher.publishEvent(UserRegisteredEvent(user.id!!))
        return user
    }

    private fun insertSocialAuth(
        userId: Long,
        provider: AuthProvider,
        response: OAuth2UserResponse,
    ): UserSocialAuth =
        conflictAs(ErrorType.DUPLICATE_SOCIAL_ACCOUNT) {
            userSocialAuthRepository.save(
                UserSocialAuth(
                    userId = userId,
                    provider = provider,
                    sub = response.socialId,
                    email = response.email,
                    displayName = response.name,
                    transferSub = response.transferInfo,
                ),
            )
        }

    private fun save(
        user: User,
        onConflict: ErrorType,
    ) {
        conflictAs(onConflict) { userRepository.saveAndFlush(user) }
    }

    private fun publishCredentialChanged(user: User) {
        eventPublisher.publishEvent(UserCredentialChangedEvent(user.id!!))
    }
}
