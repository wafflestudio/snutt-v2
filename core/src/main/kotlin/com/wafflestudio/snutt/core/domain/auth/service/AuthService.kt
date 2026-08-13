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
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.user.service.UserNicknameService
import org.springframework.beans.factory.annotation.Value
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
    private val legacyCredentialHasher: LegacyCredentialHasher,
    private val userNicknameService: UserNicknameService,
    private val passwordEncoder: PasswordEncoder,
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
    ): Pair<User, TokenPair> {
        if (!localId.matches(PasswordPolicy.localIdRegex)) throw SnuttException(ErrorType.INVALID_LOCAL_ID)
        if (!PasswordPolicy.isValidPassword(password)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        email?.let { if (!it.trim().matches(emailRegex)) throw SnuttException(ErrorType.INVALID_EMAIL) }
        if (userRepository.existsByLocalIdAndActiveTrue(localId)) throw SnuttException(ErrorType.DUPLICATE_LOCAL_ID)

        val user =
            User(
                email = email?.trim(),
                nickname = userNicknameService.generateUniqueRandomNickname(),
                localId = localId,
                localPw = passwordEncoder.encode(password),
                credentialHash = "",
            )
        user.credentialHash = legacyCredentialHasher.hash(user)
        userRepository.save(user)
        return user to issueTokens(user)
    }

    @Transactional
    fun loginLocal(
        localId: String,
        password: String,
    ): Pair<User, TokenPair> {
        val user = userRepository.findByLocalIdAndActiveTrue(localId) ?: throw SnuttException(ErrorType.WRONG_LOCAL_ID)
        if (!passwordEncoder.matches(password, user.localPw)) throw SnuttException(ErrorType.WRONG_PASSWORD)
        user.lastLoginAt = Instant.now()
        return user to issueTokens(user)
    }

    @Transactional
    fun loginSocial(
        provider: AuthProvider,
        token: String,
    ): Pair<User, TokenPair> {
        val oauth2Client = checkNotNull(oauth2Clients[provider]) { "unsupported provider: $provider" }
        val response = oauth2Client.getMe(token) ?: throw SnuttException(ErrorType.SOCIAL_CONNECT_FAIL)

        val user = findBySocialResponse(provider, response) ?: createSocialUser(provider, response)
        user.lastLoginAt = Instant.now()
        return user to issueTokens(user)
    }

    // 재사용 감지의 전체 세션 폐기는 롤백되어서는 안 된다 — SnuttException으로 나가도 커밋한다
    @Transactional(noRollbackFor = [SnuttException::class])
    fun refresh(refreshToken: String): Pair<User, TokenPair> {
        val refreshTokenHash = sha256Hex(refreshToken)
        val session =
            userSessionRepository.findWithUserByRefreshTokenHash(refreshTokenHash)
                ?: throw SnuttException(ErrorType.INVALID_REFRESH_TOKEN)
        val user = session.user

        // 회전을 조건부 UPDATE로 확정한다. 동시 요청 중 하나만 성공하므로
        // 실패한 쪽은 이미 회전된 토큰의 재사용이다 (rotate-on-use)
        if (userSessionRepository.revokeIfActive(refreshTokenHash, Instant.now()) == 0) {
            // 폐기된 토큰의 재사용은 탈취 신호로 보고 사용자 세션 전체를 폐기한다.
            // 만료만 된 경우는 탈취 근거가 아니므로 그대로 거절한다
            if (session.revokedAt != null) userSessionRepository.revokeAllByUserId(user.id!!)
            throw SnuttException(ErrorType.INVALID_REFRESH_TOKEN)
        }
        return user to issueTokens(user, session)
    }

    /**
     * access token이 가리키는 세션이 살아있는지까지 보고 사용자를 돌려준다.
     * 로그아웃·비밀번호 초기화·탈취 감지로 폐기된 세션의 토큰은 만료 전이라도 받지 않는다.
     */
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

    // v1 호환: x-access-token은 credentialHash 그 자체다
    @Transactional(readOnly = true)
    fun authenticateLegacyToken(credentialHash: String): User =
        userRepository.findByCredentialHashAndActiveTrue(credentialHash)
            ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN)

    @Transactional
    fun logout(
        sessionExternalId: String,
        fcmRegistrationId: String?,
    ) {
        userSessionRepository.findByExternalId(sessionExternalId)?.let { it.revokedAt = Instant.now() }
        fcmRegistrationId?.let { registrationId ->
            userSessionRepository
                .findByExternalId(sessionExternalId)
                ?.user
                ?.id
                ?.let { userId ->
                    userDeviceRepository
                        .findByUserIdAndFcmRegistrationIdAndIsDeletedFalse(userId, registrationId)
                        ?.let { it.isDeleted = true }
                }
        }
    }

    // 구 클라이언트가 보유한 v1 토큰(credentialHash)으로 v2 토큰 쌍을 발급하는 업그레이드 경로
    @Transactional
    fun exchangeLegacyToken(credentialHash: String): Pair<User, TokenPair> {
        val user =
            userRepository.findByCredentialHashAndActiveTrue(credentialHash)
                ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN)
        return user to issueTokens(user)
    }

    @Transactional
    fun revokeAllSessions(userId: Long) {
        userSessionRepository.revokeAllByUserId(userId)
    }

    // v1 계정 관리 이식: 로컬 계정 연결 (POST /password)
    @Transactional
    fun attachLocal(
        user: User,
        localId: String,
        password: String,
    ): String {
        if (user.localId != null) throw SnuttException(ErrorType.ALREADY_LOCAL_ACCOUNT)
        if (!localId.matches(PasswordPolicy.localIdRegex)) throw SnuttException(ErrorType.INVALID_LOCAL_ID)
        if (!PasswordPolicy.isValidPassword(password)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        if (userRepository.existsByLocalIdAndActiveTrue(localId)) throw SnuttException(ErrorType.DUPLICATE_LOCAL_ID)
        user.localId = localId
        user.localPw = passwordEncoder.encode(password)
        user.credentialHash = legacyCredentialHasher.hash(user)
        userRepository.save(user)
        return user.credentialHash
    }

    // v1 계정 관리 이식: 소셜 계정 연결 (POST /facebook|google|kakao|apple)
    @Transactional
    fun attachSocial(
        user: User,
        provider: AuthProvider,
        token: String,
    ): String {
        val oauth2Client = checkNotNull(oauth2Clients[provider]) { "unsupported provider: $provider" }
        val response = oauth2Client.getMe(token) ?: throw SnuttException(ErrorType.SOCIAL_CONNECT_FAIL)
        if (response.email != null) {
            val presentUser = userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(response.email)
            if (presentUser != null && presentUser.id != user.id) throw SnuttException(ErrorType.DUPLICATE_EMAIL)
        }
        when (provider) {
            AuthProvider.FACEBOOK -> {
                if (user.facebookSub != null) throw SnuttException(ErrorType.ALREADY_SOCIAL_ACCOUNT)
                if (userRepository.existsByFacebookSubAndActiveTrue(
                        response.socialId,
                    )
                ) {
                    throw SnuttException(ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
                }
                user.facebookSub = response.socialId
                user.facebookName = response.name
            }
            AuthProvider.GOOGLE -> {
                if (user.googleSub != null) throw SnuttException(ErrorType.ALREADY_SOCIAL_ACCOUNT)
                if (userRepository.existsByGoogleSubAndActiveTrue(
                        response.socialId,
                    )
                ) {
                    throw SnuttException(ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
                }
                user.googleSub = response.socialId
                user.googleEmail = response.email
            }
            AuthProvider.KAKAO -> {
                if (user.kakaoSub != null) throw SnuttException(ErrorType.ALREADY_SOCIAL_ACCOUNT)
                if (userRepository.existsByKakaoSubAndActiveTrue(
                        response.socialId,
                    )
                ) {
                    throw SnuttException(ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
                }
                user.kakaoSub = response.socialId
                user.kakaoEmail = response.email
            }
            AuthProvider.APPLE -> {
                if (user.appleSub != null) throw SnuttException(ErrorType.ALREADY_SOCIAL_ACCOUNT)
                if (userRepository.existsByAppleSubAndActiveTrue(
                        response.socialId,
                    )
                ) {
                    throw SnuttException(ErrorType.DUPLICATE_SOCIAL_ACCOUNT)
                }
                user.appleSub = response.socialId
                user.appleEmail = response.email
                user.appleTransferSub = response.transferInfo
            }
            AuthProvider.LOCAL -> throw IllegalArgumentException("LOCAL is not a social provider")
        }
        user.credentialHash = legacyCredentialHasher.hash(user)
        userRepository.save(user)
        return user.credentialHash
    }

    // v1 계정 관리 이식: 소셜 계정 해제 (DELETE /facebook|google|kakao|apple)
    @Transactional
    fun detachSocial(
        user: User,
        provider: AuthProvider,
    ): String {
        val attached = user.authProviders
        if (provider !in attached) throw SnuttException(ErrorType.SOCIAL_PROVIDER_NOT_ATTACHED)
        if (attached.size == 1) throw SnuttException(ErrorType.CANNOT_REMOVE_LAST_AUTH_PROVIDER)
        when (provider) {
            AuthProvider.FACEBOOK -> {
                user.facebookSub = null
                user.facebookName = null
            }
            AuthProvider.GOOGLE -> {
                user.googleSub = null
                user.googleEmail = null
            }
            AuthProvider.KAKAO -> {
                user.kakaoSub = null
                user.kakaoEmail = null
            }
            AuthProvider.APPLE -> {
                user.appleSub = null
                user.appleEmail = null
                user.appleTransferSub = null
            }
            AuthProvider.LOCAL -> throw IllegalArgumentException("LOCAL is not a social provider")
        }
        user.credentialHash = legacyCredentialHasher.hash(user)
        userRepository.save(user)
        return user.credentialHash
    }

    // v1 계정 관리 이식: 비밀번호 변경 (PUT /password)
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
        user.credentialHash = legacyCredentialHasher.hash(user)
        userRepository.save(user)
        return user.credentialHash
    }

    private fun issueTokens(
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

    private fun createSocialUser(
        provider: AuthProvider,
        response: OAuth2UserResponse,
    ): User {
        val user =
            User(
                email = response.email,
                isEmailVerified = response.email != null && response.isEmailVerified,
                nickname = userNicknameService.generateUniqueRandomNickname(),
                credentialHash = "",
            )
        when (provider) {
            AuthProvider.FACEBOOK -> {
                user.facebookSub = response.socialId
                user.facebookName = response.name
            }
            AuthProvider.GOOGLE -> {
                user.googleSub = response.socialId
                user.googleEmail = response.email
            }
            AuthProvider.KAKAO -> {
                user.kakaoSub = response.socialId
                user.kakaoEmail = response.email
            }
            AuthProvider.APPLE -> {
                user.appleSub = response.socialId
                user.appleEmail = response.email
                user.appleTransferSub = response.transferInfo
            }
            AuthProvider.LOCAL -> throw IllegalArgumentException("LOCAL is not a social provider")
        }
        user.credentialHash = legacyCredentialHasher.hash(user)
        return userRepository.save(user)
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
