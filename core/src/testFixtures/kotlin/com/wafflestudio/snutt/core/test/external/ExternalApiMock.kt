package com.wafflestudio.snutt.core.test.external

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar

/**
 * 외부 HTTP API를 대신하는 WireMock 서버. JVM당 하나만 띄워 Spring 컨텍스트 캐시 키가 바뀌지 않게 한다.
 * 서비스마다 경로 접두사([ExternalService.prefix])로 나눠 한 서버에 모은다. stub은 매 테스트 전에 `AbstractIntegrationTest`가 지운다.
 */
object ExternalApiMock {
    val server: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

    fun baseUrl(service: ExternalService): String = "${server.baseUrl()}/${service.prefix}"

    /** stub을 걸 때 쓰는 경로. 프로덕션 클라이언트가 붙이는 경로 앞에 서비스 접두사를 붙인다. */
    fun path(
        service: ExternalService,
        path: String,
    ): String = "/${service.prefix}$path"
}

enum class ExternalService(
    val prefix: String,
) {
    SUGANG_SNU("sugang-snu"),
    SNU_MAP("snu-map"),
    GITHUB("github"),
    GOOGLE("google"),
    KAKAO("kakao"),
    FACEBOOK_GRAPH("facebook-graph"),
    FACEBOOK("facebook"),
    APPLE("apple"),
}

/** 외부 API base URL 설정을 [ExternalApiMock]으로 돌린다. 모듈에 없는 설정이 섞여 있어도 쓰이지 않을 뿐 문제없다. */
@TestConfiguration(proxyBeanMethods = false)
class ExternalApiMockConfig {
    @Bean
    fun externalApiProperties(): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("snutt.sugang.base-url") { ExternalApiMock.baseUrl(ExternalService.SUGANG_SNU) }
            registry.add("snutt.snumap.base-url") { ExternalApiMock.baseUrl(ExternalService.SNU_MAP) }
            registry.add("snutt.github.base-url") { ExternalApiMock.baseUrl(ExternalService.GITHUB) }
            registry.add("snutt.auth.social.google-base-url") { ExternalApiMock.baseUrl(ExternalService.GOOGLE) }
            registry.add("snutt.auth.social.kakao-base-url") { ExternalApiMock.baseUrl(ExternalService.KAKAO) }
            registry.add("snutt.auth.social.facebook-graph-base-url") { ExternalApiMock.baseUrl(ExternalService.FACEBOOK_GRAPH) }
            registry.add("snutt.auth.social.facebook-jwks-uri") {
                ExternalApiMock.baseUrl(ExternalService.FACEBOOK) + FACEBOOK_JWKS_PATH
            }
            registry.add("snutt.auth.social.apple-jwks-uri") { ExternalApiMock.baseUrl(ExternalService.APPLE) + APPLE_JWKS_PATH }
        }

    companion object {
        const val FACEBOOK_JWKS_PATH = "/.well-known/oauth/openid/jwks/"
        const val APPLE_JWKS_PATH = "/auth/keys"
    }
}
