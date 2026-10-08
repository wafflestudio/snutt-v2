package com.wafflestudio.snutt.api

import com.wafflestudio.snutt.core.common.storage.UploadUriIssuer
import com.wafflestudio.snutt.core.test.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.ResponseEntity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractApiIntegrationTest : AbstractIntegrationTest() {
    @MockitoBean
    lateinit var uploadUriIssuer: UploadUriIssuer

    @LocalServerPort
    var port = 0

    protected val jsonMapper: JsonMapper = JsonMapper.builder().build()

    protected fun client(): RestClient =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }) { _, _ -> }
            .defaultHeader("x-os-type", "ios")
            .defaultHeader("x-client-key", "test-ios-key")
            .defaultHeader("Content-Type", "application/json")
            .build()

    protected fun post(
        uri: String,
        body: String? = null,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().post().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(String::class.java)
    }

    protected fun get(
        uri: String,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().get().uri(uri)
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
