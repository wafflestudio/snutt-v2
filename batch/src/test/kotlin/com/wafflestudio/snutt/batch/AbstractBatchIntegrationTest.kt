package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.push.PushSendResult
import com.wafflestudio.snutt.core.common.storage.UploadUriIssuer
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer

@SpringBootTest
abstract class AbstractBatchIntegrationTest {
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

    @BeforeEach
    fun resetState() {
        whenever(pushClient.sendMessages(any())).thenReturn(PushSendResult())
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
        redisTemplate.execute { it.serverCommands().flushAll() }
    }
}
