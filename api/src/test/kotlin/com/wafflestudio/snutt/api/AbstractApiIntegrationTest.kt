package com.wafflestudio.snutt.api

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.storage.UploadUriIssuer
import com.wafflestudio.snutt.core.domain.auth.oidc.OidcJwtVerifier
import com.wafflestudio.snutt.core.test.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.ResponseEntity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * api 모듈 통합테스트 베이스. 컨텍스트가 한 번만 뜨도록 이 모듈의 모든 @MockitoBean·프로퍼티는 여기에만 둔다.
 * 스텁하지 않은 mock은 null/기본값을 돌려주고, 매 테스트 뒤 Mockito가 reset한다.
 * 소셜 로그인 제공자와 GitHub는 빈 mock 대신 `wireMock`(네트워크 경계)으로 stub한다. read-timeout은 타임아웃 테스트가
 * 빨리 끝나도록 1초로 줄인다(운영 5초).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["snutt.scheduling.enabled=false", "spring.http.clients.read-timeout=1s"],
)
abstract class AbstractApiIntegrationTest : AbstractIntegrationTest() {
    @MockitoBean
    lateinit var uploadUriIssuer: UploadUriIssuer

    @LocalServerPort
    var port = 0

    @Autowired
    private lateinit var oidcJwtVerifier: OidcJwtVerifier

    /** JWKS 캐시가 앞 테스트의 응답을 들고 있으면 JWKS 장애·키 교체 테스트가 순서에 따라 달라진다. */
    @BeforeEach
    fun clearOidcCache() {
        oidcJwtVerifier.clearCache()
    }

    protected val jsonMapper: JsonMapper = JsonMapper.builder().build()

    protected fun client(language: Language? = null): RestClient {
        val builder =
            RestClient
                .builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }) { _, _ -> }
                .defaultHeader("x-os-type", "ios")
                .defaultHeader("x-client-key", "test-ios-key")
                .defaultHeader("Content-Type", "application/json")
        language?.let { builder.defaultHeader("x-language", it.name.lowercase()) }
        return builder.build()
    }

    protected fun post(
        uri: String,
        body: String? = null,
        token: String? = null,
        language: Language? = null,
    ): ResponseEntity<String> {
        val spec = client(language).post().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(String::class.java)
    }

    protected fun get(
        uri: String,
        token: String? = null,
        language: Language? = null,
    ): ResponseEntity<String> {
        val spec = client(language).get().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(String::class.java)
    }

    protected fun put(
        uri: String,
        body: String? = null,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().put().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(String::class.java)
    }

    protected fun patch(
        uri: String,
        body: String? = null,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().patch().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(String::class.java)
    }

    protected fun delete(
        uri: String,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().delete().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(String::class.java)
    }

    protected fun body(response: ResponseEntity<String>): JsonNode = jsonMapper.readTree(response.body!!)

    protected fun register(
        localId: String,
        email: String = "$localId@snu.ac.kr",
    ): String {
        val response = post("/v2/auth/register", """{"localId":"$localId","password":"password1","email":"$email"}""")
        assertEquals(200, response.statusCode.value())
        return body(response)["accessToken"].asString()
    }
}
