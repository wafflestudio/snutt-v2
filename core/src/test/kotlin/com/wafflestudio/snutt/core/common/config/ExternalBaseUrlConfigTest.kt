package com.wafflestudio.snutt.core.common.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

/**
 * 테스트는 외부 base URL을 모두 WireMock으로 돌리므로, yml의 운영값이 틀려도 통합테스트로는 드러나지 않는다.
 * 설정값으로 빼기 전 코드에 박혀 있던 URL과 yml 값(+ 클라이언트가 붙이는 경로)이 같은지 고정한다.
 */
class ExternalBaseUrlConfigTest {
    private val properties =
        YamlPropertiesFactoryBean()
            .apply { setResources(ClassPathResource("application-core.yml")) }
            .getObject()!!

    private fun property(key: String): String = requireNotNull(properties.getProperty(key)) { "$key 설정이 없다" }

    @Test
    fun `소셜 로그인 제공자 URL은 기존 상수와 같다`() {
        assertEquals("https://www.googleapis.com/oauth2/v1/userinfo", property("snutt.auth.social.google-base-url") + "/oauth2/v1/userinfo")
        assertEquals("https://kapi.kakao.com/v2/user/me", property("snutt.auth.social.kakao-base-url") + "/v2/user/me")
        assertEquals("https://graph.facebook.com/me", property("snutt.auth.social.facebook-graph-base-url") + "/me")
        assertEquals("https://www.facebook.com/.well-known/oauth/openid/jwks/", property("snutt.auth.social.facebook-jwks-uri"))
        assertEquals("https://appleid.apple.com/auth/keys", property("snutt.auth.social.apple-jwks-uri"))
    }

    @Test
    fun `GitHub와 강의계획서 프록시 URL은 운영값이다`() {
        assertEquals("https://api.github.com", property("snutt.github.base-url"))
        assertEquals("wafflestudio/snutt-feedbacks", property("snutt.github.repo-owner") + "/" + property("snutt.github.repo-name"))
        assertEquals("https://snutt-proxy.wafflestudio.com", property("snutt.syllabus-proxy.base-url"))
    }
}
