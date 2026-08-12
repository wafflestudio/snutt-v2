package com.wafflestudio.snutt.core.domain.auth.client

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import com.wafflestudio.snutt.core.domain.auth.oidc.OidcJwtVerifier
import com.wafflestudio.snutt.core.domain.auth.oidc.OidcVerificationOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component("APPLE")
class AppleClient(
    private val oidcJwtVerifier: OidcJwtVerifier,
    @param:Value("\${snutt.auth.oidc.apple-app-id:}") private val appleAppId: String,
) : OAuth2Client {
    companion object {
        private const val APPLE_JWK_URI = "https://appleid.apple.com/auth/keys"
        private const val APPLE_ISSUER = "https://appleid.apple.com"
    }

    override fun getMe(token: String): OAuth2UserResponse? {
        if (!oidcJwtVerifier.looksLikeJwt(token)) throw SnuttException(ErrorType.INVALID_APPLE_LOGIN_TOKEN)

        val claims =
            oidcJwtVerifier.verifyAndDecodeToken(
                token = token,
                options =
                    OidcVerificationOptions(
                        jwksUri = APPLE_JWK_URI,
                        expectedIssuer = APPLE_ISSUER,
                        expectedAudience = appleAppId,
                    ),
            ) ?: return null

        return OAuth2UserResponse(
            socialId = claims.subject,
            name = null,
            email = claims["email"] as? String,
            isEmailVerified = claims["email_verified"] as? Boolean ?: true,
            transferInfo = claims["transfer_sub"] as? String,
        )
    }
}
