package com.wafflestudio.snutt.core.domain.auth.client

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.http.TimedRestClients
import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.domain.auth.OAuth2UserResponse
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

private data class KakaoOAuth2UserResponse(
    val id: Long,
    @param:JsonProperty("kakao_account")
    val kakaoAccount: KakaoAccountDto,
)

private data class KakaoAccountDto(
    val email: String,
    @param:JsonProperty("is_email_verified")
    val isEmailVerified: Boolean,
)

@Component("KAKAO")
class KakaoClient : OAuth2Client {
    private val restClient = TimedRestClients.restClient()

    companion object {
        private const val USER_INFO_URI = "https://kapi.kakao.com/v2/user/me"
    }

    override fun getMe(token: String): OAuth2UserResponse? {
        val response =
            try {
                restClient
                    .get()
                    .uri(USER_INFO_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .body(KakaoOAuth2UserResponse::class.java)
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
            socialId = response.id.toString(),
            email = response.kakaoAccount.email,
            isEmailVerified = response.kakaoAccount.isEmailVerified,
            name = null,
        )
    }
}
