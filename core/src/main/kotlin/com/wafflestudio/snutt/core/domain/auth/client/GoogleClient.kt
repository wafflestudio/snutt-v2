package com.wafflestudio.snutt.core.domain.auth.client

import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import com.wafflestudio.snutt.core.domain.auth.fetchSocialProfile
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

private data class GoogleOAuth2UserResponse(
    val id: String,
    val email: String,
)

@Component("GOOGLE")
class GoogleClient(
    private val restClient: RestClient,
    @param:Value("\${snutt.auth.social.google-base-url}") private val baseUrl: String,
) : OAuth2Client {
    override fun getMe(token: String): OAuth2UserResponse {
        val response =
            fetchSocialProfile("google") {
                restClient
                    .get()
                    .uri("$baseUrl/oauth2/v1/userinfo")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .body(GoogleOAuth2UserResponse::class.java)
            }
        return OAuth2UserResponse(socialId = response.id, email = response.email, name = null)
    }
}
