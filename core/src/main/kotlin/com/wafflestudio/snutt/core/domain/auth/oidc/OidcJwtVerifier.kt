package com.wafflestudio.snutt.core.domain.auth.oidc

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.http.TimedRestClients
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Date

data class OidcVerificationOptions(
    val jwksUri: String,
    val expectedIssuer: String? = null,
    val expectedAudience: String? = null,
)

data class OidcJwkSet(
    val keys: List<OidcJwk>,
)

data class OidcJwk(
    val kty: String = "",
    val kid: String = "",
    val alg: String = "",
    val n: String = "",
    val e: String = "",
)

private data class OidcJwtHeader(
    val kid: String,
    val alg: String,
)

@Component
class OidcJwtVerifier(
    private val objectMapper: ObjectMapper,
) {
    private val restClient = TimedRestClients.restClient()

    fun verifyAndDecodeToken(
        token: String,
        options: OidcVerificationOptions,
    ): Claims? {
        val jwtHeader = runCatching { extractJwtHeader(token) }.getOrNull() ?: return null
        val oidcJwk =
            try {
                fetchJwk(jwtHeader, options.jwksUri)
            } catch (_: RestClientException) {
                throw SnuttException(ErrorType.SOCIAL_PROVIDER_UNAVAILABLE)
            } ?: return null
        val publicKey = convertJwkToPublicKey(oidcJwk)
        val claims =
            try {
                parseSignedClaims(token, publicKey)
            } catch (_: JwtException) {
                return null
            }
        if (!isValidIssuer(claims, options.expectedIssuer)) return null
        if (!isValidAudience(claims, options.expectedAudience)) return null
        if (!isNotExpired(claims)) return null
        return claims
    }

    fun looksLikeJwt(token: String): Boolean {
        val parts = token.split(".")
        return parts.size == 3 && parts.none { it.isBlank() }
    }

    private fun fetchJwk(
        jwtHeader: OidcJwtHeader,
        jwksUri: String,
    ): OidcJwk? = fetchJwkSet(jwksUri)?.keys?.find { matches(it, jwtHeader) }

    private fun fetchJwkSet(jwksUri: String): OidcJwkSet? =
        restClient
            .get()
            .uri(jwksUri)
            .retrieve()
            .body(OidcJwkSet::class.java)

    private fun matches(
        jwk: OidcJwk,
        header: OidcJwtHeader,
    ): Boolean = jwk.kid == header.kid && (jwk.alg == header.alg || jwk.alg.isBlank())

    private fun extractJwtHeader(token: String): OidcJwtHeader? {
        if (!looksLikeJwt(token)) return null

        val headerJson = Base64.getUrlDecoder().decode(token.substringBefore(".")).toString(Charsets.UTF_8)
        val headerMap: Map<String, String?> = objectMapper.readValue(headerJson)
        val kid = headerMap["kid"] ?: return null
        val alg = headerMap["alg"] ?: return null

        return OidcJwtHeader(kid = kid, alg = alg)
    }

    private fun convertJwkToPublicKey(jwk: OidcJwk): PublicKey {
        require(jwk.kty == "RSA") { "unsupported kty: ${jwk.kty}" }

        val modulus = BigInteger(1, Base64.getUrlDecoder().decode(jwk.n))
        val exponent = BigInteger(1, Base64.getUrlDecoder().decode(jwk.e))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
    }

    private fun parseSignedClaims(
        token: String,
        publicKey: PublicKey,
    ) = Jwts
        .parser()
        .verifyWith(publicKey)
        .build()
        .parseSignedClaims(token)
        .payload

    private fun isValidIssuer(
        claims: Claims,
        expectedIssuer: String?,
    ): Boolean = expectedIssuer == null || (claims["iss"] as? String) == expectedIssuer

    private fun isValidAudience(
        claims: Claims,
        expectedAudience: String?,
    ): Boolean {
        if (expectedAudience == null) return true

        return when (val audience = claims["aud"] ?: return false) {
            is String -> audience == expectedAudience
            is Collection<*> -> audience.any { it == expectedAudience }
            else -> false
        }
    }

    private fun isNotExpired(claims: Claims): Boolean {
        val expiration = claims.expiration ?: return false
        return expiration.after(Date())
    }
}
