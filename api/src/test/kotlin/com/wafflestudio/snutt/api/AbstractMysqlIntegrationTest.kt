package com.wafflestudio.snutt.api

import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.push.PushSendResult
import com.wafflestudio.snutt.core.common.storage.UploadUriIssuer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractMysqlIntegrationTest {
    companion object {
        @JvmStatic
        val mysql: MySQLContainer = MySQLContainer("mysql:26.7").apply { start() }

        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer("valkey/valkey:9-alpine").withExposedPorts(6379).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun containerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }
    }

    @MockitoBean
    lateinit var pushClient: PushClient

    @MockitoBean
    lateinit var mailClient: MailClient

    @MockitoBean
    lateinit var uploadUriIssuer: UploadUriIssuer

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @LocalServerPort
    var port = 0

    protected val jsonMapper: JsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun resetState() {
        whenever(pushClient.sendMessages(any())).thenReturn(PushSendResult())
        clearAllTables()
        redisTemplate.execute { it.serverCommands().flushAll() }
    }

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

    private fun clearAllTables() {
        val tables =
            jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'",
                String::class.java,
            )
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0")
        try {
            tables.forEach { table ->
                when (table) {
                    "flyway_schema_history" -> Unit
                    "timetable_theme" -> jdbcTemplate.execute("DELETE FROM `$table` WHERE builtin_code IS NULL")
                    else -> jdbcTemplate.execute("DELETE FROM `$table`")
                }
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1")
        }
    }
}
