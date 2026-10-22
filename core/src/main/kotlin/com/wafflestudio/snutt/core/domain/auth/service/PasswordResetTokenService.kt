package com.wafflestudio.snutt.core.domain.auth.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.Date

data class PasswordResetTokenPayload(
    val userId: Long,
    val tokenVersion: Int,
)

@Service
class PasswordResetTokenService(
    private val es256Keys: Es256Keys,
    @param:Value("\${snutt.auth.jwt.password-reset-token-ttl}") private val passwordResetTokenTtl: Duration,
) {
    companion object {
        private const val ISSUER = "snutt"
        private const val TYPE_CLAIM = "typ"
        private const val TOKEN_TYPE = "password-reset"
        private const val TOKEN_VERSION_CLAIM = "ver"
    }

    fun issue(payload: PasswordResetTokenPayload): String {
        val now = Instant.now()
        return Jwts
            .builder()
            .issuer(ISSUER)
            .subject(payload.userId.toString())
            .claim(TYPE_CLAIM, TOKEN_TYPE)
            .claim(TOKEN_VERSION_CLAIM, payload.tokenVersion)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now + passwordResetTokenTtl))
            .signWith(es256Keys.privateKey, Jwts.SIG.ES256)
            .compact()
    }

    fun verify(token: String): PasswordResetTokenPayload {
        val claims: Claims =
            try {
                Jwts
                    .parser()
                    .verifyWith(es256Keys.publicKey)
                    .requireIssuer(ISSUER)
                    .require(TYPE_CLAIM, TOKEN_TYPE)
                    .build()
                    .parseSignedClaims(token)
                    .payload
            } catch (e: Exception) {
                throw SnuttException(ErrorType.INVALID_PASSWORD_RESET_TOKEN)
            }
        return PasswordResetTokenPayload(
            userId = claims.subject?.toLongOrNull() ?: throw SnuttException(ErrorType.INVALID_PASSWORD_RESET_TOKEN),
            tokenVersion =
                claims.get(TOKEN_VERSION_CLAIM, Int::class.javaObjectType)
                    ?: throw SnuttException(ErrorType.INVALID_PASSWORD_RESET_TOKEN),
        )
    }
}
