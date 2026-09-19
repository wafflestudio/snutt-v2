package com.wafflestudio.snutt.core.domain.auth

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.UpstreamException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

interface OAuth2Client {
    fun getMe(token: String): OAuth2UserResponse?
}

data class OAuth2UserResponse(
    val socialId: String,
    val name: String?,
    val email: String?,
    val transferInfo: String? = null,
)

fun <T> fetchSocialProfile(
    provider: String,
    request: () -> T?,
): T =
    try {
        request() ?: throw SnuttException(ErrorType.SOCIAL_CONNECT_FAIL)
    } catch (e: RestClientException) {
        if (e is RestClientResponseException && e.statusCode.is4xxClientError) {
            throw SnuttException(ErrorType.SOCIAL_CONNECT_FAIL)
        }
        throw UpstreamException(ErrorType.SOCIAL_PROVIDER_UNAVAILABLE, provider, e)
    }
