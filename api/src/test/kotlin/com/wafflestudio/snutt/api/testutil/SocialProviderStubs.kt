package com.wafflestudio.snutt.api.testutil

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.wafflestudio.snutt.core.test.external.ExternalApiMock
import com.wafflestudio.snutt.core.test.external.ExternalApiMockConfig
import com.wafflestudio.snutt.core.test.external.ExternalService

/** 소셜 로그인 제공자와 GitHub Issues API stub. 응답 본문은 `fixtures/<서비스>/`에 있다. */
class SocialProviderStubs(
    private val wireMock: WireMockServer,
) {
    fun googleUserInfo(
        token: String,
        response: ResponseDefinitionBuilder,
    ) {
        wireMock.stubFor(
            get(urlPathEqualTo(GOOGLE_USER_INFO)).withHeader("Authorization", equalTo("Bearer $token")).willReturn(response),
        )
    }

    fun kakaoUserMe(
        token: String,
        response: ResponseDefinitionBuilder,
    ) {
        wireMock.stubFor(get(urlPathEqualTo(KAKAO_USER_ME)).withHeader("Authorization", equalTo("Bearer $token")).willReturn(response))
    }

    fun facebookMe(
        token: String,
        response: ResponseDefinitionBuilder,
    ) {
        wireMock.stubFor(get(urlPathEqualTo(FACEBOOK_ME)).withQueryParam("access_token", equalTo(token)).willReturn(response))
    }

    fun appleJwks(response: ResponseDefinitionBuilder = json(OidcTestIssuer.apple.jwks())) {
        wireMock.stubFor(get(urlPathEqualTo(APPLE_JWKS)).willReturn(response))
    }

    fun facebookJwks(response: ResponseDefinitionBuilder = json(OidcTestIssuer.facebook.jwks())) {
        wireMock.stubFor(get(urlPathEqualTo(FACEBOOK_JWKS)).willReturn(response))
    }

    fun githubCreateIssue(response: ResponseDefinitionBuilder) {
        wireMock.stubFor(post(urlPathEqualTo(GITHUB_ISSUES)).willReturn(response))
    }

    fun requestsTo(path: String): Int = wireMock.allServeEvents.count { it.request.url.substringBefore("?") == path }

    companion object {
        val GOOGLE_USER_INFO = ExternalApiMock.path(ExternalService.GOOGLE, "/oauth2/v1/userinfo")
        val KAKAO_USER_ME = ExternalApiMock.path(ExternalService.KAKAO, "/v2/user/me")
        val FACEBOOK_ME = ExternalApiMock.path(ExternalService.FACEBOOK_GRAPH, "/me")
        val APPLE_JWKS = ExternalApiMock.path(ExternalService.APPLE, ExternalApiMockConfig.APPLE_JWKS_PATH)
        val FACEBOOK_JWKS = ExternalApiMock.path(ExternalService.FACEBOOK, ExternalApiMockConfig.FACEBOOK_JWKS_PATH)
        val GITHUB_ISSUES = ExternalApiMock.path(ExternalService.GITHUB, "/repos/wafflestudio/snutt-feedbacks/issues")

        fun fixture(path: String): String =
            requireNotNull(SocialProviderStubs::class.java.getResourceAsStream("/fixtures/$path")) { "fixture 없음: $path" }
                .use { it.readBytes().decodeToString() }

        fun json(
            body: String,
            status: Int = 200,
        ): ResponseDefinitionBuilder =
            aResponse().withStatus(status).withHeader("Content-Type", "application/json; charset=utf-8").withBody(body)

        fun status(code: Int): ResponseDefinitionBuilder = aResponse().withStatus(code).withBody("upstream error")

        /** read-timeout(테스트 1초)보다 늦는 응답. */
        fun slow(response: ResponseDefinitionBuilder): ResponseDefinitionBuilder = response.withFixedDelay(1_500)
    }
}
