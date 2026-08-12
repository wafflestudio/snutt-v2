package com.wafflestudio.snutt.core.domain.auth.client

import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import com.wafflestudio.snutt.core.domain.auth.oidc.OidcJwtVerifier
import com.wafflestudio.snutt.core.domain.auth.oidc.OidcVerificationOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

private data class FacebookOAuth2UserResponse(
    val id: String,
    val email: String?,
    val name: String?,
)

@Component("FACEBOOK")
class FacebookClient(
    private val oidcJwtVerifier: OidcJwtVerifier,
    @param:Value("\${snutt.auth.oidc.facebook-app-id:}") private val facebookAppId: String,
) : OAuth2Client {
    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient = RestClient.create()

    companion object {
        private const val USER_INFO_URI = "https://graph.facebook.com/me"
        private const val FACEBOOK_JWK_URI = "https://www.facebook.com/.well-known/oauth/openid/jwks/"
        private const val FACEBOOK_ISSUER = "https://www.facebook.com"
    }

    override fun getMe(token: String): OAuth2UserResponse? {
        if (oidcJwtVerifier.looksLikeJwt(token)) {
            getMeFromAuthenticationToken(token)?.let { return it }
        }
        return getMeFromAccessToken(token)
    }

    private fun getMeFromAccessToken(token: String): OAuth2UserResponse? {
        val response =
            runCatching {
                restClient
                    .get()
                    .uri("$USER_INFO_URI?access_token={token}", token)
                    .retrieve()
                    .body(FacebookOAuth2UserResponse::class.java)
            }.onFailure { log.warn("facebook getMe failed: {}", it.message) }
                .getOrNull() ?: return null

        return OAuth2UserResponse(
            socialId = response.id,
            name = response.name,
            email = response.email,
            isEmailVerified = true,
        )
    }

    private fun getMeFromAuthenticationToken(token: String): OAuth2UserResponse? {
        val claims =
            oidcJwtVerifier.verifyAndDecodeToken(
                token = token,
                options =
                    OidcVerificationOptions(
                        jwksUri = FACEBOOK_JWK_URI,
                        expectedIssuer = FACEBOOK_ISSUER,
                        expectedAudience = facebookAppId,
                    ),
            ) ?: return null

        return OAuth2UserResponse(
            socialId = claims["sub"] as? String ?: return null,
            name = claims["name"] as? String,
            email = claims["email"] as? String,
            isEmailVerified = claims["email_verified"] as? Boolean ?: true,
        )
    }
}
