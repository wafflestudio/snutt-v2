package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.concurrency.ConcurrencyScenario
import com.wafflestudio.snutt.api.concurrency.ConcurrencyScenarios
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.service.AccessTokenPayload
import com.wafflestudio.snutt.core.domain.auth.service.AccessTokenService
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.test.concurrency.ConcurrentOutcome
import com.wafflestudio.snutt.core.test.concurrency.runConcurrently
import com.wafflestudio.snutt.core.test.stress.FailureKind
import com.wafflestudio.snutt.core.test.stress.MySqlProbe
import com.wafflestudio.snutt.core.test.stress.ServerErrorLog
import com.wafflestudio.snutt.core.test.stress.StressRun
import com.wafflestudio.snutt.core.test.stress.StressSettings
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * api stress 테스트 베이스. RANDOM_PORT로 뜬 실제 서버에 [StressHttp]로 요청한다. 어노테이션을 더하지 않으므로 api 컨텍스트를 그대로 쓴다
 * (Hikari 풀·Tomcat 설정이 운영 설정 파일과 같다. 운영과 다른 것은 api 베이스의 read-timeout과 스케줄러 off뿐이다).
 * 하위 클래스에는 `@StressTest`(또는 재현된 버그는 `@StressKnownFailure`)를 붙인다.
 */
abstract class AbstractStressTest : AbstractApiIntegrationTest() {
    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var appJsonMapper: JsonMapper

    @Autowired
    private lateinit var accessTokenService: AccessTokenService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var springEnvironment: Environment

    protected val settings: StressSettings = StressSettings.fromSystemProperties()

    protected val http: StressHttp by lazy { StressHttp("http://localhost:$port", appJsonMapper) }

    private val tokens = ConcurrentHashMap<Long, String>()

    /** fixtures로 만든 사용자의 액세스 토큰. 가입 API를 거치지 않고 서버와 같은 키로 발급한다. */
    protected fun token(userId: Long): String =
        tokens.computeIfAbsent(userId) {
            val user = userRepository.findByIdOrNull(userId) ?: error("user $userId 가 없다")
            accessTokenService.issue(AccessTokenPayload(userId, user.tokenVersion))
        }

    /** E의 race 시나리오를 HTTP로 보낸다. */
    protected val httpScenarios: ConcurrencyScenarios by lazy {
        ConcurrencyScenarios(applicationContext, fixtures, HttpScenarioActions(http, ::token))
    }

    /** 시나리오 하나를 기록하며 돌린다. 리포트를 쓰고, 실패가 있으면 AssertionError로 끝난다. */
    protected fun stress(
        scenario: String,
        block: StressRun.() -> Unit,
    ) {
        val environment =
            MySqlProbe.environment(dataSource) +
                mapOf(
                    "spring.threads.virtual.enabled(Tomcat 요청 스레드)" to
                        springEnvironment.getProperty("spring.threads.virtual.enabled", "false"),
                    "server.tomcat.threads.max" to springEnvironment.getProperty("server.tomcat.threads.max", "기본값(가상 스레드면 무시)"),
                )
        ServerErrorLog().use { serverErrors ->
            val run = StressRun(scenario, settings, environment, serverErrors)
            run.block()
            run.finish()
        }
    }

    /** 동시 실행 결과를 연산 [operation] 이름으로 기록한다. 5xx·정해지지 않은 예외는 허용하지 않는다. */
    protected fun StressRun.recordOutcome(
        operation: String,
        outcome: ConcurrentOutcome<Unit>,
    ) {
        outcome.results.forEach { result ->
            val error = result.exceptionOrNull()
            when {
                error == null -> record(operation, "2xx", allowed = true)
                error is SnuttException -> record(operation, error.error.name, allowed = true)
                error is UnexpectedHttpResponse ->
                    record(operation, error.response.status.toString(), allowed = false, detail = error.message.orEmpty())
                else -> {
                    record(operation, error.javaClass.simpleName, allowed = false, detail = FailureKind.summarize(error))
                }
            }
        }
    }

    /** 반복마다 DB·Redis를 비운다. 서버가 들고 있는 토큰 캐시는 사용자 id가 바뀌므로 함께 비운다. */
    protected fun resetForIteration() {
        resetStores()
        tokens.clear()
    }

    /** race 시나리오 하나를 반복마다 처음 상태부터 돌린다. 요청 수는 [StressSettings.threads]다. */
    protected fun runRaceIterations(
        run: StressRun,
        operation: String,
        timeout: Duration,
        scenario: (requests: Int) -> ConcurrencyScenario,
    ) {
        for (iteration in 1..settings.iterations) {
            if (run.shouldStop()) break
            run.iteration(iteration) {
                resetForIteration()
                val prepared = scenario(settings.threads)
                val outcome =
                    try {
                        runConcurrently(prepared.requests, timeout, action = prepared::request)
                    } catch (e: AssertionError) {
                        run.fail(FailureKind.CLIENT_TIMEOUT, operation, e.message.orEmpty())
                        return@iteration
                    }
                run.recordOutcome(operation, outcome)
                prepared.verifyUnordered(outcome)
            }
        }
    }
}
