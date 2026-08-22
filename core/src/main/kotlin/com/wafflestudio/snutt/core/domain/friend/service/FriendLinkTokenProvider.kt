package com.wafflestudio.snutt.core.domain.friend.service

import com.wafflestudio.snutt.core.domain.auth.service.Es256Keys
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * 친구 초대 링크용 stateless 토큰.
 * 토큰 하나를 여러 명이 사용할 수 있으므로(멀티 유즈) 상태를 저장하지 않고
 * 액세스 토큰과 같은 ES256 키로 서명한 JWT에 typ 클레임으로 용도를 구분해 담는다.
 * 무효화가 필요해지면 상태 저장 방식으로 되돌려야 한다.
 */
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

    /** 서명·용도·만료가 유효하면 초대한 사용자 id, 아니면 null */
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
