package com.wafflestudio.snutt.core.test

import com.github.tomakehurst.wiremock.WireMockServer
import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailSendOutcome
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.push.PushSendResult
import com.wafflestudio.snutt.core.test.external.ExternalApiMock
import com.wafflestudio.snutt.core.test.external.ExternalApiMockConfig
import com.wafflestudio.snutt.core.test.fixture.ScenarioFixtures
import com.wafflestudio.snutt.core.test.fixture.ScenarioFixturesConfig
import com.wafflestudio.snutt.core.test.query.QueryCountConfig
import com.wafflestudio.snutt.core.test.time.MutableClock
import com.wafflestudio.snutt.core.test.time.TestClockConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * 모든 Spring 통합테스트의 루트. 컨텍스트 캐시를 모듈당 하나로 유지하려면
 * 하위 클래스에 @MockitoBean·@Import·@TestPropertySource를 새로 달지 말고 모듈 베이스(AbstractApiIntegrationTest 등)에 모은다.
 */
@Import(
    IntegrationContainers::class,
    ExternalApiMockConfig::class,
    TestClockConfig::class,
    QueryCountConfig::class,
    ScenarioFixturesConfig::class,
)
abstract class AbstractIntegrationTest {
    @MockitoBean
    lateinit var pushClient: PushClient

    @MockitoBean
    lateinit var mailClient: MailClient

    /** 기본은 시스템 시각을 따른다. 시간 의존 테스트는 setInstant/setKst로 고정한다. 매 테스트 전에 reset된다. */
    @Autowired
    lateinit var clock: MutableClock

    /** 외부 HTTP API mock. 매 테스트 전에 stub과 요청 기록을 지운다. 경로는 `ExternalApiMock.path(service, path)`로 만든다. */
    val wireMock: WireMockServer get() = ExternalApiMock.server

    @Autowired
    lateinit var fixtures: ScenarioFixtures

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun resetState() {
        clock.reset()
        ExternalApiMock.server.resetAll()
        whenever(pushClient.sendMessages(any())).thenReturn(PushSendResult())
        whenever(mailClient.send(any(), any(), any())).thenReturn(MailSendOutcome.SENT)
        resetStores()
    }

    /** 모든 테이블을 비우고(빌트인 테마 제외) Redis를 비운다. stress 테스트는 반복마다 부른다. */
    protected fun resetStores() {
        clearAllTables()
        redisTemplate.execute { it.serverCommands().flushAll() }
    }

    /**
     * stub과 맞지 않은 요청이 없어야 한다. 404를 삼키는 클라이언트(SNU 지도 등)가 있어 stub 경로가 틀려도 테스트가 통과할 수 있으므로
     * 모든 통합테스트에서 따로 본다. 404 자체를 검증하려면 stub으로 404를 돌려준다.
     */
    @AfterEach
    fun noUnmatchedExternalRequests() {
        assertEquals(emptyList<String>(), ExternalApiMock.server.findAllUnmatchedRequests().map { "${it.method} ${it.url}" })
    }

    /**
     * FOREIGN_KEY_CHECKS는 세션 변수라 SET과 DELETE가 같은 커넥션에서 돌아야 한다. JdbcTemplate.execute(String)은 호출마다
     * 풀에서 커넥션을 새로 받을 수 있으므로 ConnectionCallback 하나 안에서 모두 실행한다.
     */
    private fun clearAllTables() {
        jdbcTemplate.execute(
            ConnectionCallback { connection ->
                connection.createStatement().use { statement ->
                    val tables =
                        statement
                            .executeQuery(
                                "SELECT TABLE_NAME FROM information_schema.TABLES " +
                                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'",
                            ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                    statement.execute("SET FOREIGN_KEY_CHECKS = 0")
                    try {
                        tables.forEach { table ->
                            when (table) {
                                "flyway_schema_history" -> Unit
                                "timetable_theme" -> statement.execute("DELETE FROM `$table` WHERE builtin_code IS NULL")
                                else -> statement.execute("DELETE FROM `$table`")
                            }
                        }
                    } finally {
                        statement.execute("SET FOREIGN_KEY_CHECKS = 1")
                    }
                }
            },
        )
    }
}
