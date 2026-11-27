package com.wafflestudio.snutt.core.test

import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailSendOutcome
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.push.PushSendResult
import com.wafflestudio.snutt.core.test.fixture.ScenarioFixtures
import com.wafflestudio.snutt.core.test.fixture.ScenarioFixturesConfig
import com.wafflestudio.snutt.core.test.query.QueryCountConfig
import com.wafflestudio.snutt.core.test.time.MutableClock
import com.wafflestudio.snutt.core.test.time.TestClockConfig
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * 모든 Spring 통합테스트의 루트. 컨텍스트 캐시를 모듈당 하나로 유지하려면
 * 하위 클래스에 @MockitoBean·@Import·@TestPropertySource를 새로 달지 말고 모듈 베이스(AbstractApiIntegrationTest 등)에 모은다.
 */
@Import(IntegrationContainers::class, TestClockConfig::class, QueryCountConfig::class, ScenarioFixturesConfig::class)
abstract class AbstractIntegrationTest {
    @MockitoBean
    lateinit var pushClient: PushClient

    @MockitoBean
    lateinit var mailClient: MailClient

    /** 기본은 시스템 시각을 따른다. 시간 의존 테스트는 setInstant/setKst로 고정한다. 매 테스트 전에 reset된다. */
    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var fixtures: ScenarioFixtures

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun resetState() {
        clock.reset()
        whenever(pushClient.sendMessages(any())).thenReturn(PushSendResult())
        whenever(mailClient.send(any(), any(), any())).thenReturn(MailSendOutcome.SENT)
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
