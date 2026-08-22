package com.wafflestudio.snutt.core.domain.friend.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 친구 초대 링크용 stateless 서명 토큰.
 * 토큰 하나를 여러 명이 사용할 수 있으므로(멀티 유즈) 상태를 저장하지 않고
 * userId와 만료 시각에 HMAC 서명만 붙인다. 무효화가 필요해지면 상태 저장 방식으로 되돌려야 한다.
 */
@Component
class FriendLinkTokenProvider(
    @Value("\${snutt.friend-link.secret}") secret: String,
) {
    private val mac: Mac =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        }

    fun issue(
        userId: Long,
        ttl: Duration = VALIDITY,
    ): String {
        val expiresAtEpochSecond = Instant.now().plus(ttl).epochSecond
        val payload = "$userId.$expiresAtEpochSecond"
        return "$payload.${sign(payload)}"
    }

    /** 서명과 만료 시각이 유효하면 초대한 사용자 id, 아니면 null */
    fun parse(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val (userId, expiresAtEpochSecond, signature) = parts
        if (signature != sign("$userId.$expiresAtEpochSecond")) return null
        val expiresAt =
            expiresAtEpochSecond.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
        if (!Instant.now().isBefore(expiresAt)) return null
        return userId.toLongOrNull()
    }

    private fun sign(payload: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))

    companion object {
        private val VALIDITY: Duration = Duration.ofDays(14)
    }
}
