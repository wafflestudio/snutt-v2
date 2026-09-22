package com.wafflestudio.snutt.api.misc

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvalidParameterIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("invalid_parameter_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @LocalServerPort
    var port = 0

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var accessToken: String

    @BeforeAll
    fun register() {
        val response =
            client()
                .post()
                .uri("/v2/auth/register")
                .body("""{"localId":"paramuser","password":"password1","email":"param@snu.ac.kr"}""")
                .retrieve()
                .toEntity(String::class.java)
        accessToken = body(response)["accessToken"].asString()
    }

    private fun client(): RestClient =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }) { _, _ -> }
            .defaultHeader("x-os-type", "ios")
            .defaultHeader("x-client-key", "test-ios-key")
            .defaultHeader("Content-Type", "application/json")
            .build()

    private fun get(uri: String): ResponseEntity<String> =
        client()
            .get()
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .retrieve()
            .toEntity(String::class.java)

    private fun body(response: ResponseEntity<String>): JsonNode = jsonMapper.readTree(response.body!!)

    private fun assertInvalidParameter(response: ResponseEntity<String>) {
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(40001L, body(response)["errcode"].asLong())
    }

    @Test
    fun `범위를 벗어난 학기 값은 400으로 응답한다`() {
        assertInvalidParameter(get("/v2/timetables/2026/9"))
    }

    @Test
    fun `숫자가 아닌 학기 값은 400으로 응답한다`() {
        assertInvalidParameter(get("/v2/timetables/2026/abc"))
        assertInvalidParameter(get("/v2/bookmarks?year=2026&semester=abc"))
    }
}
