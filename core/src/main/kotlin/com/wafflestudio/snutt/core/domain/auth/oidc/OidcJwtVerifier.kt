package com.wafflestudio.snutt.core.domain.auth.oidc

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.UpstreamException
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.ProtectedHeader
import io.jsonwebtoken.security.JwkSet
import io.jsonwebtoken.security.Jwks
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.security.Key
import java.util.concurrent.ConcurrentHashMap

@Component
class OidcJwtVerifier(
    private val restClient: RestClient,
) {
    private val jwkSets = ConcurrentHashMap<String, JwkSet>()

    fun verifyAndDecodeToken(
        token: String,
        jwksUri: String,
        issuer: String,
        audience: String,
    ): Claims? =
        try {
            Jwts
                .parser()
                .keyLocator { header -> (header as? ProtectedHeader)?.keyId?.let { findKey(jwksUri, it) } }
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (_: JwtException) {
            null
        }

    fun looksLikeJwt(token: String): Boolean {
        val parts = token.split(".")
        return parts.size == 3 && parts.none { it.isBlank() }
    }

    private fun findKey(
        jwksUri: String,
        keyId: String,
    ): Key? {
        jwkSets[jwksUri]?.findKey(keyId)?.let { return it }
        val fetched = fetchJwkSet(jwksUri) ?: return null
        jwkSets[jwksUri] = fetched
        return fetched.findKey(keyId)
    }

    private fun fetchJwkSet(jwksUri: String): JwkSet? {
        val json =
            try {
                restClient
                    .get()
                    .uri(jwksUri)
                    .retrieve()
                    .body(String::class.java)
            } catch (e: RestClientException) {
                throw UpstreamException(ErrorType.SOCIAL_PROVIDER_UNAVAILABLE, "oidc", e)
            }
        return json?.let { Jwks.setParser().build().parse(it) }
    }

    private fun JwkSet.findKey(keyId: String): Key? = getKeys().firstOrNull { it.id == keyId }?.toKey()
}
