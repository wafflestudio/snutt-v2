package com.wafflestudio.snutt.core.test

import org.junit.jupiter.api.Tag

/**
 * `./gradlew test`에서 제외되고 전용 태스크(`parityTest`, `perfTest`, `stressTest`, `liveTest`)로만 도는 JUnit 태그.
 * 루트 build.gradle.kts의 태그 목록과 값이 같아야 한다.
 */
object TestTags {
    const val PARITY = "parity"
    const val PERF = "perf"
    const val STRESS = "stress"
    const val LIVE = "live"
}

/** v1 서버와 v2(v1compat) 응답을 비교하는 테스트. `./gradlew parityTest`로 돈다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.PARITY)
annotation class ParityTest

/** 운영 분포 데이터(snutt_perf)에서 실행계획을 검증하는 테스트. `./gradlew perfTest`로 돈다. 쿼리 수 검증은 태그 없이 PR CI에서 돈다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.PERF)
annotation class PerfTest

/** 대량 데이터·동시성 부하를 거는 테스트. `./gradlew stressTest`로 돈다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.STRESS)
annotation class StressTest

/**
 * 실제 외부 사이트를 호출하는 smoke 테스트. `./gradlew liveTest`로만 돌고 CI에서는 돌지 않는다.
 * 수강편람 사이트는 잦은 요청에 IP를 막을 수 있으므로 요청 수를 최소로 유지한다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.LIVE)
annotation class LiveTest
