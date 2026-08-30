package com.wafflestudio.snutt.core.domain.auth.client

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.http.TimedRestClients
import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

private data class GoogleOAuth2UserResponse(
    val id: String,
    val email: String,
)

@Component("GOOGLE")
class GoogleClient : OAuth2Client {
    private val restClient = TimedRestClients.restClient()

    companion object {
        private const val USER_INFO_URI = "https://www.googleapis.com/oauth2/v1/userinfo"
    }

    override fun getMe(token: String): OAuth2UserResponse? {
        val response =
            try {
                restClient
                    .get()
                    .uri(USER_INFO_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .body(GoogleOAuth2UserResponse::class.java)
                    ?: throw SnuttException(ErrorType.SOCIAL_PROVIDER_UNAVAILABLE)
            } catch (e: RestClientException) {
                throw SnuttException(
                    if (e is RestClientResponseException && e.statusCode.is4xxClientError) {
                        ErrorType.SOCIAL_CONNECT_FAIL
                    } else {
                        ErrorType.SOCIAL_PROVIDER_UNAVAILABLE
                    },
                )
            }

        return OAuth2UserResponse(
            socialId = response.id,
            email = response.email,
            isEmailVerified = true,
            name = null,
        )
    }
}
