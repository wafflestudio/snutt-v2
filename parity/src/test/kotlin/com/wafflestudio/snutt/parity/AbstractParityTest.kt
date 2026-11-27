package com.wafflestudio.snutt.parity

import com.wafflestudio.snutt.core.common.storage.UploadUriIssuer
import com.wafflestudio.snutt.core.domain.auth.OAuth2Client
import com.wafflestudio.snutt.core.test.AbstractIntegrationTest
import com.wafflestudio.snutt.parity.compare.AllowedDiffs
import com.wafflestudio.snutt.parity.env.ParityContainers
import com.wafflestudio.snutt.parity.env.ParityDataSource
import com.wafflestudio.snutt.parity.env.ParityEnvironment
import com.wafflestudio.snutt.parity.env.ParityReports
import com.wafflestudio.snutt.parity.env.PreparedData
import com.wafflestudio.snutt.parity.scenario.ParityScenario
import com.wafflestudio.snutt.parity.scenario.Side
import com.wafflestudio.snutt.parity.seed.SeedDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * parity 시나리오 베이스. v2 api는 이 JVM에서 RANDOM_PORT로, v1 두 서버는 컨테이너로 뜬다([ParityContainers]).
 * 매 테스트 전에 v1 저장소를 시드로 다시 채우고 실제 migration 단계로 v2에 옮기므로 시나리오끼리 독립이다.
 *
 * 컨텍스트 캐시 규칙(docs/testing.md)은 이 모듈에도 적용된다. mock과 프로퍼티는 여기에만 둔다.
 * mock 구성은 api 베이스(AbstractApiIntegrationTest)와 같다.
 */
@SpringBootTest(
    classes = [ParityV2Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["snutt.scheduling.enabled=false"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractParityTest : AbstractIntegrationTest() {
    @MockitoBean
    lateinit var uploadUriIssuer: UploadUriIssuer

    @MockitoBean(name = "GOOGLE")
    lateinit var googleClient: OAuth2Client

    @MockitoBean(name = "APPLE")
    lateinit var appleClient: OAuth2Client

    @LocalServerPort
    var port = 0

    @Autowired
    lateinit var v2Jdbc: JdbcTemplate

    lateinit var data: PreparedData

    /** 입력 데이터. 기본은 코드 시드. */
    protected open val source: ParityDataSource get() = SeedDataSource

    /**
     * true면 known-failures.yml의 차이를 실패 대신 "알려진 버그"로 기록한다. 버그를 재현하는 @ParityKnownFailure 클래스는
     * false로 둬서 그 차이로 실패하게 한다.
     */
    protected open val tolerateKnownFailures: Boolean get() = true

    @BeforeEach
    fun prepareParityData() {
        data = ParityEnvironment.prepare(source, v2Jdbc)
    }

    @AfterAll
    fun writeSummary() {
        ParityReports.writeSummary(allowedDiffs)
    }

    protected fun parity(
        name: String,
        block: ParityScenario.() -> Unit,
    ) {
        val scenario =
            ParityScenario(
                name = name,
                targets = mapOf(Side.V1 to ParityContainers.v1SnuttUrl, Side.V2 to "http://localhost:$port"),
                allowed = allowedDiffs,
                knownFailures = if (tolerateKnownFailures) knownFailures else null,
                knownIds = data.knownIds,
                initialVars = data.vars,
                preparedSince = data.preparedSince,
            )
        try {
            scenario.block()
        } catch (failure: Throwable) {
            // 중간에 멈춘 시나리오도 거기까지의 리포트와 차이를 남긴다.
            runCatching { scenario.finish() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        scenario.finish()
    }

    companion object {
        val allowedDiffs: AllowedDiffs by lazy { AllowedDiffs.load() }
        val knownFailures: AllowedDiffs by lazy { AllowedDiffs.load(AllowedDiffs.KNOWN_FAILURES) }
    }
}
