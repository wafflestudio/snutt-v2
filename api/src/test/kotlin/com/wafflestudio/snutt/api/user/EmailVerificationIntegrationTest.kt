package com.wafflestudio.snutt.api.user

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.core.common.mail.RecordingMailClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailVerificationIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("email_verify_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var recordingMailClient: RecordingMailClient

    @LocalServerPort
    var port = 0

    private lateinit var token: String

    @BeforeAll
    fun registerUser() {
        val response =
            post(
                "/v2/auth/register",
                """{"localId":"emailuser","password":"password1","email":"temp@snu.ac.kr"}""",
            )
        token = asMap(response)["accessToken"] as String
    }

    @BeforeEach
    fun clean() {
        recordingMailClient.sentMails.clear()
    }

    private fun client(): RestClient =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }) { _, _ -> }
            .defaultHeader("x-client-platform", "ios")
            .defaultHeader("x-client-key", "test-ios-key")
            .defaultHeader("Content-Type", "application/json")
            .build()

    @Suppress("UNCHECKED_CAST")
    private fun post(
        uri: String,
        body: String,
    ): ResponseEntity<Any> =
        client()
            .post()
            .uri(uri)
            .headers { if (::token.isInitialized) it.setBearerAuth(token) }
            .body(body)
            .retrieve()
            .toEntity(Any::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun get(uri: String): ResponseEntity<Any> =
        client()
            .get()
            .uri(uri)
            .headers { it.setBearerAuth(token) }
            .retrieve()
            .toEntity(Any::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun delete(uri: String): ResponseEntity<Any> =
        client()
            .delete()
            .uri(uri)
            .headers { it.setBearerAuth(token) }
            .retrieve()
            .toEntity(Any::class.java)

    private fun asMap(response: ResponseEntity<Any>): Map<String, Any?> = response.body as Map<String, Any?>

    @Test
    fun `SNU 메일이 아니면 인증 코드를 발송하지 않는다`() {
        val response = post("/v2/users/me/email/verification", """{"email":"foo@gmail.com"}""")
        assertEquals(403, response.statusCode.value())
        assertTrue(recordingMailClient.sentMails.isEmpty())
    }

    @Test
    fun `인증 코드 발송과 검증과 리셋`() {
        val send = post("/v2/users/me/email/verification", """{"email":"emailuser@snu.ac.kr"}""")
        assertEquals(200, send.statusCode.value())
        assertTrue(recordingMailClient.sentMails.isNotEmpty())
        val (email, code) = recordingMailClient.sentMails[0]
        assertEquals("emailuser@snu.ac.kr", email)
        assertEquals(6, code.length)

        val wrong = post("/v2/users/me/email/verification/code", """{"code":"000000"}""")
        assertEquals(400, wrong.statusCode.value())

        val verify = post("/v2/users/me/email/verification/code", """{"code":"$code"}""")
        assertEquals(200, verify.statusCode.value())
        assertEquals(true, asMap(verify)["isEmailVerified"])

        val again = post("/v2/users/me/email/verification", """{"email":"emailuser@snu.ac.kr"}""")
        assertEquals(400, again.statusCode.value())

        val reset = delete("/v2/users/me/email/verification")
        assertEquals(false, asMap(reset)["isEmailVerified"])
        assertEquals(false, asMap(get("/v2/users/me/email/verification"))["isEmailVerified"])
    }

    @Test
    fun `v1 경로에서도 이메일 인증이 동작한다`() {
        val register =
            post("/v1/auth/register_local", """{"id":"v1emailuser","password":"password1","email":"v1temp@snu.ac.kr"}""")
        val v1Token = asMap(register)["token"] as String

        val send =
            client()
                .post()
                .uri("/v1/user/email/verification")
                .header("x-access-token", v1Token)
                .body("""{"email":"v1email@snu.ac.kr"}""")
                .retrieve()
                .toEntity(Any::class.java)
        assertEquals(200, send.statusCode.value())
        val code = recordingMailClient.sentMails[0].second

        val verify =
            client()
                .post()
                .uri("/v1/user/email/verification/code")
                .header("x-access-token", v1Token)
                .body("""{"code":"$code"}""")
                .retrieve()
                .toEntity(Any::class.java)
        assertEquals(200, verify.statusCode.value())
        assertEquals(true, asMap(verify)["isEmailVerified"])
    }
}
