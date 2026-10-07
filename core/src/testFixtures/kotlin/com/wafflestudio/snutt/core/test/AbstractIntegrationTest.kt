package com.wafflestudio.snutt.core.test

import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.push.PushSendResult
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

@Import(IntegrationContainers::class)
abstract class AbstractIntegrationTest {
    @MockitoBean
    lateinit var pushClient: PushClient

    @MockitoBean
    lateinit var mailClient: MailClient

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun resetState() {
        whenever(pushClient.sendMessages(any())).thenReturn(PushSendResult())
        clearAllTables()
        redisTemplate.execute { it.serverCommands().flushAll() }
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
