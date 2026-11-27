package com.wafflestudio.snutt.api.testutil

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Jwks
import tools.jackson.databind.json.JsonMapper
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Apple·Facebook OIDC를 흉내 내는 발급자. 테스트용 RSA 키쌍으로 JWKS와 서명된 id_token을 만든다.
 * 키는 JVM당 한 번 만든다. `OidcJwtVerifier`가 JWKS를 URI별로 캐시하므로 테스트마다 키를 바꾸면 캐시와 어긋난다.
 * 새 키가 필요한 테스트(키 교체, JWKS 장애)는 [rotatedKey]처럼 kid가 다른 키를 쓴다.
 */
class OidcTestIssuer(
    val issuer: String,
    val audience: String,
) {
    class SigningKey(
        val kid: String,
        val keyPair: KeyPair,
    )

    val key =
        SigningKey(
            "$PREFIX-1",
            Jwts.SIG.RS256
                .keyPair()
                .build(),
        )

    /** JWKS 캐시에 없는 kid. 이 키로 서명한 토큰은 JWKS를 다시 받아야 검증된다. */
    fun rotatedKey(kid: String) =
        SigningKey(
            kid,
            Jwts.SIG.RS256
                .keyPair()
                .build(),
        )

    /** 공급자 문서의 JWKS 형태(`{"keys":[{kty, kid, use, alg, n, e}]}`). */
    fun jwks(vararg keys: SigningKey = arrayOf(key)): String =
        jsonMapper.writeValueAsString(
            mapOf(
                "keys" to
                    keys.map { signing ->
                        Jwks
                            .builder()
                            .key(signing.keyPair.public as RSAPublicKey)
                            .id(signing.kid)
                            .publicKeyUse("sig")
                            .algorithm("RS256")
                            .build()
                    },
            ),
        )

    /**
     * id_token. 만료는 jjwt가 시스템 시각으로 검사하므로(Clock 빈을 따르지 않는다) 실제 현재 시각 기준으로 만든다.
     */
    fun idToken(
        subject: String,
        claims: Map<String, Any> = emptyMap(),
        signingKey: SigningKey = key,
        audience: String = this.audience,
        issuer: String = this.issuer,
        expiresIn: Duration = Duration.ofMinutes(10),
    ): String {
        val now = Instant.now()
        return Jwts
            .builder()
            .header()
            .keyId(signingKey.kid)
            .and()
            .issuer(issuer)
            .audience()
            .add(audience)
            .and()
            .subject(subject)
            .claims(claims)
            .issuedAt(Date.from(now.minus(Duration.ofMinutes(1))))
            .expiration(Date.from(now.plus(expiresIn)))
            .signWith(signingKey.keyPair.private, Jwts.SIG.RS256)
            .compact()
    }

    companion object {
        private const val PREFIX = "test-key"
        private val jsonMapper = JsonMapper.builder().build()

        /** AppleClient가 기대하는 iss와 테스트 프로필의 앱 ID. */
        val apple = OidcTestIssuer("https://appleid.apple.com", "test-apple-app-id")

        /** FacebookClient(Limited Login)가 기대하는 iss와 테스트 프로필의 앱 ID. */
        val facebook = OidcTestIssuer("https://www.facebook.com", "test-facebook-app-id")
    }
}
