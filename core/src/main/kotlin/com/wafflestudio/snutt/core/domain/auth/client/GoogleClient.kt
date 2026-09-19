package com.wafflestudio.snutt.core.domain.auth.client

import com.wafflestudio.snutt.core.common.http.TimedRestClients
import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import com.wafflestudio.snutt.core.domain.auth.fetchSocialProfile
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component

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

    override fun getMe(token: String): OAuth2UserResponse {
        val response =
            fetchSocialProfile("google") {
                restClient
                    .get()
                    .uri(USER_INFO_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .body(GoogleOAuth2UserResponse::class.java)
            }
        return OAuth2UserResponse(socialId = response.id, email = response.email, name = null)
    }
}
