package com.wafflestudio.snutt.core.test

import org.junit.jupiter.api.Tag

/**
 * `./gradlew test`에서 제외되고 전용 태스크(`parityTest`, `perfTest`, `stressTest`)로만 도는 JUnit 태그.
 * 루트 build.gradle.kts의 태그 목록과 값이 같아야 한다.
 */
object TestTags {
    const val PARITY = "parity"
    const val PERF = "perf"
    const val STRESS = "stress"
}

/** v1 서버와 v2(v1compat) 응답을 비교하는 테스트. `./gradlew parityTest`로 돈다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.PARITY)
annotation class ParityTest

/** 실행계획·쿼리 수처럼 결정적인 성능 지표를 검증하는 테스트. `./gradlew perfTest`로 돈다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.PERF)
annotation class PerfTest

/** 대량 데이터·동시성 부하를 거는 테스트. `./gradlew stressTest`로 돈다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.STRESS)
annotation class StressTest
