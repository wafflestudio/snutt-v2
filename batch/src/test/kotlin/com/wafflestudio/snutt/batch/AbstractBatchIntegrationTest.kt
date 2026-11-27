package com.wafflestudio.snutt.batch

import com.wafflestudio.snutt.core.test.AbstractIntegrationTest
import org.springframework.boot.test.context.SpringBootTest

/**
 * batch 모듈 통합테스트 베이스. 컨텍스트가 한 번만 뜨도록 이 모듈의 @MockitoBean·프로퍼티는 여기에만 둔다.
 * 수강편람 사이트와 SNU 지도는 빈 mock 대신 `wireMock`(네트워크 경계)으로 stub한다. 헬퍼는 `SugangSnuStubs`.
 *
 * - read-timeout은 타임아웃 테스트가 빨리 끝나도록 1초로 줄인다(운영 30초).
 * - 빈자리 잡의 대기 시간(청크 간격, 부하 시 재시도 간격)도 테스트에서는 거의 0으로 줄인다.
 */
@SpringBootTest(
    properties = [
        "spring.http.clients.read-timeout=1s",
        "snutt.vacancy.chunk-delay=0ms",
        "snutt.vacancy.overload-retry-delay=10ms",
    ],
)
abstract class AbstractBatchIntegrationTest : AbstractIntegrationTest() {
    val sugang: SugangSnuStubs get() = SugangSnuStubs(wireMock)
}
