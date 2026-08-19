package com.wafflestudio.snutt.core.domain.auth.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

data class AccessTokenPayload(
    val userId: Long,
    val sessionId: Long,
)

@Service
class AccessTokenService(
    @Value("\${snutt.auth.jwt.private-key}") privateKeyBase64: String,
    @Value("\${snutt.auth.jwt.public-key}") publicKeyBase64: String,
    @param:Value("\${snutt.auth.jwt.access-token-ttl:PT2H}") private val accessTokenTtl: Duration,
) {
    private val privateKey: PrivateKey =
        KeyFactory
            .getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)))
    private val publicKey: PublicKey =
        KeyFactory
            .getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))

    companion object {
        private const val ISSUER = "snutt"
        private const val SESSION_CLAIM = "sid"
    }

    fun issue(payload: AccessTokenPayload): String {
        val now = Instant.now()
        return Jwts
            .builder()
            .issuer(ISSUER)
            .subject(payload.userId.toString())
            .claim(SESSION_CLAIM, payload.sessionId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now + accessTokenTtl))
            .signWith(privateKey, Jwts.SIG.ES256)
            .compact()
    }

    fun verify(token: String): AccessTokenPayload {
        val claims: Claims =
            try {
                Jwts
                    .parser()
                    .verifyWith(publicKey)
                    .requireIssuer(ISSUER)
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
            sessionId = (claims[SESSION_CLAIM] as? String)?.toLongOrNull() ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN),
        )
    }
}
