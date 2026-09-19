package com.wafflestudio.snutt.v1compat.auth

import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 구 백엔드는 80비트짜리 짧은 secret 으로 HS256 apikey JWT 를 발급했다.
 * jjwt 는 256비트 미만 키를 거부하므로(RFC 7518 §3.2) 서명 검증을 직접 한다.
 */
class LegacyApiKeyVerifier(
    secretKey: String,
) {
    private val key: SecretKeySpec? =
        secretKey.takeIf { it.isNotBlank() }?.let { SecretKeySpec(it.toByteArray(), MAC_ALGORITHM) }

    fun claimsOf(token: String): Map<String, Any?>? {
        val key = this.key ?: return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        val (header, payload, signature) = parts
        return runCatching {
            if (decodeJson(header)?.get("alg") != ALGORITHM_ID) return null
            if (!signatureMatches(key, "$header.$payload", signature)) return null
            decodeJson(payload)?.takeIf(::withinValidity)
        }.getOrNull()
    }

    private fun signatureMatches(
        key: SecretKeySpec,
        signingInput: String,
        signature: String,
    ): Boolean {
        val expected = Mac.getInstance(MAC_ALGORITHM).apply { init(key) }.doFinal(signingInput.toByteArray(Charsets.US_ASCII))
        return MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(signature))
    }

    private fun withinValidity(claims: Map<String, Any?>): Boolean {
        val now = Instant.now().epochSecond
        val expiration = (claims["exp"] as? Number)?.toLong()
        val notBefore = (claims["nbf"] as? Number)?.toLong()
        return (expiration == null || now <= expiration) && (notBefore == null || now >= notBefore)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeJson(segment: String): Map<String, Any?>? =
        jsonMapper.readValue(Base64.getUrlDecoder().decode(segment), Map::class.java) as? Map<String, Any?>

    private companion object {
        const val ALGORITHM_ID = "HS256"
        const val MAC_ALGORITHM = "HmacSHA256"
        val jsonMapper: JsonMapper = JsonMapper.builder().build()
    }
}
