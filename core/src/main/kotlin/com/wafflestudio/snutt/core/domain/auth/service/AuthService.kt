package com.wafflestudio.snutt.core.domain.auth.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.util.PasswordPolicy
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import com.wafflestudio.snutt.core.domain.auth.model.UserSession
import com.wafflestudio.snutt.core.domain.auth.repository.UserSessionRepository
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.user.event.UserCredentialChangedEvent
import com.wafflestudio.snutt.core.domain.user.event.UserRegisteredEvent
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.user.service.UserNicknameService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
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
    private val userSessionRepository: UserSessionRepository,
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

    @Transactional(readOnly = true)
    fun authenticate(payload: AccessTokenPayload): User {
        val session =
            userSessionRepository.findWithUserByExternalId(payload.sessionExternalId)
                ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN)
        if (!session.isValid) throw SnuttException(ErrorType.WRONG_USER_TOKEN)
        val user = session.user
        if (!user.active || user.externalId != payload.userExternalId) throw SnuttException(ErrorType.WRONG_USER_TOKEN)
        return user
    }

    @Transactional(noRollbackFor = [SnuttException::class])
    fun refresh(refreshToken: String): Pair<User, TokenPair> {
        val refreshTokenHash = sha256Hex(refreshToken)
        val session =
            userSessionRepository.findWithUserByRefreshTokenHash(refreshTokenHash)
                ?: throw SnuttException(ErrorType.INVALID_REFRESH_TOKEN)
        val user = session.user

        if (userSessionRepository.revokeIfActive(refreshTokenHash, Instant.now()) == 0) {
            if (session.revokedAt != null) userSessionRepository.revokeAllByUserId(user.id!!)
            throw SnuttException(ErrorType.INVALID_REFRESH_TOKEN)
        }
        return user to issueTokens(user, session)
    }

    @Transactional
    fun issueTokens(
        user: User,
        rotatedFrom: UserSession? = null,
    ): TokenPair {
        val refreshToken = generateRefreshToken()
        val session =
            userSessionRepository.save(
                UserSession(
                    user = user,
                    refreshTokenHash = sha256Hex(refreshToken),
                    userDevice = rotatedFrom?.userDevice,
                    expiresAt = Instant.now() + refreshTokenTtl,
                ),
            )
        val accessToken =
            accessTokenService.issue(
                AccessTokenPayload(
                    userExternalId = user.externalId,
                    sessionExternalId = session.externalId,
                ),
            )
        return TokenPair(accessToken = accessToken, refreshToken = refreshToken)
    }

    @Transactional
    fun logout(
        sessionExternalId: String,
        fcmRegistrationId: String?,
    ) {
        val session = userSessionRepository.findByExternalId(sessionExternalId) ?: return
        session.revokedAt = Instant.now()
        if (fcmRegistrationId == null) return
        userDeviceRepository
            .findByUserIdAndFcmRegistrationIdAndIsDeletedFalse(session.user.id!!, fcmRegistrationId)
            ?.let { it.isDeleted = true }
    }

    @Transactional
    fun revokeAllSessions(userId: Long) {
        userSessionRepository.revokeAllByUserId(userId)
    }

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
        if (provider in user.authProviders) throw SnuttException(ErrorType.ALREADY_SOCIAL_ACCOUNT)
        if (existsBySocialId(provider, response.socialId)) throw SnuttException(ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
        user.applySocial(provider, response)
        save(user, ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
        publishCredentialChanged(user)
    }

    @Transactional
    fun detachSocial(
        user: User,
        provider: AuthProvider,
    ) {
        val attached = user.authProviders
        if (provider !in attached) throw SnuttException(ErrorType.SOCIAL_PROVIDER_NOT_ATTACHED)
        if (attached.size == 1) throw SnuttException(ErrorType.CANNOT_REMOVE_LAST_AUTH_PROVIDER)
        user.clearSocial(provider)
        userRepository.save(user)
        publishCredentialChanged(user)
    }

    @Transactional
    fun changePassword(
        user: User,
        currentPassword: String,
        newPassword: String,
    ) {
        if (user.localPw == null) throw SnuttException(ErrorType.INVALID_LOCAL_ID)
        if (!passwordEncoder.matches(currentPassword, user.localPw)) throw SnuttException(ErrorType.WRONG_PASSWORD)
        if (!PasswordPolicy.isValidPassword(newPassword)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        user.localPw = passwordEncoder.encode(newPassword)
        userRepository.save(user)
        publishCredentialChanged(user)
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
    ): User? =
        when (provider) {
            AuthProvider.FACEBOOK -> userRepository.findByFacebookSubAndActiveTrue(response.socialId)
            AuthProvider.GOOGLE -> userRepository.findByGoogleSubAndActiveTrue(response.socialId)
            AuthProvider.KAKAO -> userRepository.findByKakaoSubAndActiveTrue(response.socialId)
            AuthProvider.APPLE ->
                userRepository.findByAppleSubAndActiveTrue(response.socialId)
                    ?: response.transferInfo?.let { userRepository.findByAppleTransferSubAndActiveTrue(it) }
            AuthProvider.LOCAL -> throw IllegalArgumentException("LOCAL is not a social provider")
        }

    private fun existsBySocialId(
        provider: AuthProvider,
        socialId: String,
    ): Boolean =
        when (provider) {
            AuthProvider.FACEBOOK -> userRepository.existsByFacebookSubAndActiveTrue(socialId)
            AuthProvider.GOOGLE -> userRepository.existsByGoogleSubAndActiveTrue(socialId)
            AuthProvider.KAKAO -> userRepository.existsByKakaoSubAndActiveTrue(socialId)
            AuthProvider.APPLE -> userRepository.existsByAppleSubAndActiveTrue(socialId)
            AuthProvider.LOCAL -> throw IllegalArgumentException("LOCAL is not a social provider")
        }

    private fun createSocialUser(
        provider: AuthProvider,
        response: OAuth2UserResponse,
    ): User {
        val user =
            User(
                email = response.email,
                isEmailVerified = response.email != null && response.isEmailVerified,
                nickname = userNicknameService.generateUniqueRandomNickname(),
            ).apply { applySocial(provider, response) }
        save(user, ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
        eventPublisher.publishEvent(UserRegisteredEvent(user.id!!))
        return user
    }

    private fun User.applySocial(
        provider: AuthProvider,
        response: OAuth2UserResponse,
    ) {
        when (provider) {
            AuthProvider.FACEBOOK -> {
                facebookSub = response.socialId
                facebookName = response.name
            }
            AuthProvider.GOOGLE -> {
                googleSub = response.socialId
                googleEmail = response.email
            }
            AuthProvider.KAKAO -> {
                kakaoSub = response.socialId
                kakaoEmail = response.email
            }
            AuthProvider.APPLE -> {
                appleSub = response.socialId
                appleEmail = response.email
                appleTransferSub = response.transferInfo
            }
            AuthProvider.LOCAL -> throw IllegalArgumentException("LOCAL is not a social provider")
        }
    }

    private fun User.clearSocial(provider: AuthProvider) {
        when (provider) {
            AuthProvider.FACEBOOK -> {
                facebookSub = null
                facebookName = null
            }
            AuthProvider.GOOGLE -> {
                googleSub = null
                googleEmail = null
            }
            AuthProvider.KAKAO -> {
                kakaoSub = null
                kakaoEmail = null
            }
            AuthProvider.APPLE -> {
                appleSub = null
                appleEmail = null
                appleTransferSub = null
            }
            AuthProvider.LOCAL -> throw IllegalArgumentException("LOCAL is not a social provider")
        }
    }

    private fun save(
        user: User,
        onConflict: ErrorType,
    ) {
        try {
            userRepository.saveAndFlush(user)
        } catch (e: DataIntegrityViolationException) {
            throw SnuttException(onConflict)
        }
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
