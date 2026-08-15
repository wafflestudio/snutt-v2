package com.wafflestudio.snutt.v1compat.auth

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.user.event.UserCredentialChangedEvent
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

@Service
class LegacyTokenService(
    private val legacyAccessTokenRepository: LegacyAccessTokenRepository,
    private val userRepository: UserRepository,
) {
    private val secureRandom = SecureRandom()

    @Transactional
    fun issue(user: User): String {
        val token = generateToken()
        legacyAccessTokenRepository.save(
            LegacyAccessToken(userId = user.id!!, tokenHash = sha256Hex(token)),
        )
        return token
    }

    @Transactional(readOnly = true)
    fun authenticate(token: String): User {
        val stored =
            legacyAccessTokenRepository.findByTokenHash(sha256Hex(token))
                ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN)
        return userRepository.findByIdAndActiveTrue(stored.userId)
            ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN)
    }

    @EventListener
    @Transactional
    fun revokeOnCredentialChange(event: UserCredentialChangedEvent) {
        legacyAccessTokenRepository.deleteAllByUserId(event.userId)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
        )
}
