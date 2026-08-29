package com.wafflestudio.snutt.core.domain.auth.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.common.util.PasswordPolicy
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import com.wafflestudio.snutt.core.domain.auth.model.RefreshToken
import com.wafflestudio.snutt.core.domain.auth.repository.RefreshTokenRepository
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.user.event.UserCredentialChangedEvent
import com.wafflestudio.snutt.core.domain.user.event.UserRegisteredEvent
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.model.UserSocialAuth
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserSocialAuthRepository
import com.wafflestudio.snutt.core.domain.user.service.UserNicknameService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val userSocialAuthRepository: UserSocialAuthRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val userDeviceRepository: UserDeviceRepository,
    private val accessTokenService: AccessTokenService,
    private val userNicknameService: UserNicknameService,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: ApplicationEventPublisher,
    oauth2Clients: Map<String, OAuth2Client>,
    @param:Value("\${snutt.auth.refresh-token-ttl:P180D}") private val refreshTokenTtl: Duration,
) {
    private val oauth2Clients = oauth2Clients.mapKeys { AuthProvider.valueOf(it.key) }
    private val secureRandom = SecureRandom()

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

    @Transactional
    fun refresh(refreshToken: String): Pair<User, TokenPair> {
        val presentedTokenHash = sha256Hex(refreshToken)
        val refreshTokenRecord =
            refreshTokenRepository.findWithUserByTokenHash(presentedTokenHash)
                ?: throw SnuttException(ErrorType.INVALID_REFRESH_TOKEN)
        val user = refreshTokenRecord.user
        if (!user.active) throw SnuttException(ErrorType.INVALID_REFRESH_TOKEN)

        val newRefreshToken = generateRefreshToken()
        val now = Instant.now()
        val rotatedCount =
            refreshTokenRepository.rotate(
                presentedTokenHash = presentedTokenHash,
                newTokenHash = sha256Hex(newRefreshToken),
                newExpiresAt = now + refreshTokenTtl,
                now = now,
            )
        if (rotatedCount == 0) throw SnuttException(ErrorType.INVALID_REFRESH_TOKEN)

        val accessToken = accessTokenService.issue(AccessTokenPayload(userId = user.id!!))
        return user to TokenPair(accessToken = accessToken, refreshToken = newRefreshToken)
    }

    @Transactional
    fun issueTokens(user: User): TokenPair {
        val refreshToken = generateRefreshToken()
        refreshTokenRepository.save(
            RefreshToken(
                user = user,
                tokenHash = sha256Hex(refreshToken),
                expiresAt = Instant.now() + refreshTokenTtl,
            ),
        )
        val accessToken = accessTokenService.issue(AccessTokenPayload(userId = user.id!!))
        return TokenPair(accessToken = accessToken, refreshToken = refreshToken)
    }

    @Transactional
    fun logout(
        refreshToken: String,
        fcmRegistrationId: String?,
    ) {
        val refreshTokenRecord = refreshTokenRepository.findWithUserByTokenHash(sha256Hex(refreshToken)) ?: return
        val userId = refreshTokenRecord.user.id!!
        refreshTokenRepository.delete(refreshTokenRecord)
        if (fcmRegistrationId == null) return
        userDeviceRepository
            .findByUserIdAndFcmRegistrationIdAndIsDeletedFalse(userId, fcmRegistrationId)
            ?.let { it.isDeleted = true }
    }

    @Transactional
    fun attachLocal(
        userId: Long,
        localId: String,
        password: String,
    ) {
        val user = getActiveUser(userId)
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
        userId: Long,
        provider: AuthProvider,
        token: String,
    ) {
        val user = getActiveUser(userId)
        val response = fetchSocialUser(provider, token)
        if (response.email != null) {
            val presentUser = userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(response.email)
            if (presentUser != null && presentUser.id != userId) throw SnuttException(ErrorType.DUPLICATE_EMAIL)
        }
        if (userSocialAuthRepository.findByUserIdAndProvider(userId, provider) != null) {
            throw SnuttException(ErrorType.ALREADY_SOCIAL_ACCOUNT)
        }
        if (existsBySocialId(provider, response.socialId)) throw SnuttException(ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
        insertSocialAuth(userId, provider, response)
        publishCredentialChanged(user)
    }

    @Transactional
    fun detachSocial(
        userId: Long,
        provider: AuthProvider,
    ) {
        val user = getActiveUser(userId)
        val socialProviders = userSocialAuthRepository.findByUserId(userId).map { it.provider }
        if (provider !in socialProviders) throw SnuttException(ErrorType.SOCIAL_PROVIDER_NOT_ATTACHED)
        if (socialProviders.size + (if (user.localId != null) 1 else 0) == 1) {
            throw SnuttException(ErrorType.CANNOT_REMOVE_LAST_AUTH_PROVIDER)
        }
        userSocialAuthRepository.deleteByUserIdAndProvider(userId, provider)
        publishCredentialChanged(user)
    }

    @Transactional(readOnly = true)
    fun getAuthProviders(userId: Long): List<AuthProvider> =
        buildList {
            if (getActiveUser(userId).localId != null) add(AuthProvider.LOCAL)
            userSocialAuthRepository
                .findByUserId(userId)
                .sortedBy { it.provider.ordinal }
                .forEach { add(it.provider) }
        }

    @Transactional
    fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String,
    ): TokenPair {
        val user = getActiveUser(userId)
        if (user.localPw == null) throw SnuttException(ErrorType.INVALID_LOCAL_ID)
        if (!passwordEncoder.matches(currentPassword, user.localPw)) throw SnuttException(ErrorType.WRONG_PASSWORD)
        if (!PasswordPolicy.isValidPassword(newPassword)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        user.localPw = passwordEncoder.encode(newPassword)
        userRepository.save(user)
        refreshTokenRepository.deleteAllByUserId(user.id!!)
        publishCredentialChanged(user)
        return issueTokens(user)
    }

    private fun getActiveUser(userId: Long): User =
        userRepository.findByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)

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

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
