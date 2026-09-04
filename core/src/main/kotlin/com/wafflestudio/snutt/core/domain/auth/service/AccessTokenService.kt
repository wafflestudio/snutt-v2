package com.wafflestudio.snutt.core.domain.auth.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.Date

data class AccessTokenPayload(
    val userId: Long,
)

@Service
class AccessTokenService(
    private val es256Keys: Es256Keys,
    @param:Value("\${snutt.auth.jwt.access-token-ttl:P180D}") private val accessTokenTtl: Duration,
) {
    companion object {
        private const val ISSUER = "snutt"
        private const val TYPE_CLAIM = "typ"
        private const val TOKEN_TYPE = "access"
    }

    fun issue(payload: AccessTokenPayload): String {
        val now = Instant.now()
        return Jwts
            .builder()
            .issuer(ISSUER)
            .subject(payload.userId.toString())
            .claim(TYPE_CLAIM, TOKEN_TYPE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now + accessTokenTtl))
            .signWith(es256Keys.privateKey, Jwts.SIG.ES256)
            .compact()
    }

    fun verify(token: String): AccessTokenPayload {
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
            } catch (e: ExpiredJwtException) {
                throw SnuttException(ErrorType.EXPIRED_ACCESS_TOKEN)
            } catch (e: Exception) {
                throw SnuttException(ErrorType.WRONG_USER_TOKEN)
            }
        return AccessTokenPayload(
            userId = claims.subject?.toLongOrNull() ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN),
        )
    }
}
