package com.wafflestudio.snutt.core.domain.friend.service

import com.wafflestudio.snutt.core.domain.auth.service.Es256Keys
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Date

@Component
class FriendLinkTokenProvider(
    private val es256Keys: Es256Keys,
) {
    fun issue(
        userId: Long,
        ttl: Duration = VALIDITY,
    ): String {
        val now = Instant.now()
        return Jwts
            .builder()
            .issuer(ISSUER)
            .subject(userId.toString())
            .claim(TYPE_CLAIM, TOKEN_TYPE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now + ttl))
            .signWith(es256Keys.privateKey, Jwts.SIG.ES256)
            .compact()
    }

    fun parse(token: String): Long? =
        try {
            Jwts
                .parser()
                .verifyWith(es256Keys.publicKey)
                .requireIssuer(ISSUER)
                .require(TYPE_CLAIM, TOKEN_TYPE)
                .build()
                .parseSignedClaims(token)
                .payload
                .subject
                ?.toLongOrNull()
        } catch (e: Exception) {
            null
        }

    companion object {
        private const val ISSUER = "snutt"
        private const val TYPE_CLAIM = "typ"
        private const val TOKEN_TYPE = "friend-link"
        private val VALIDITY: Duration = Duration.ofDays(14)
    }
}
