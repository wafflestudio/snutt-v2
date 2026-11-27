package com.wafflestudio.snutt.core.test

import org.junit.jupiter.api.Tag

/**
 * `./gradlew test`에서 제외되고 전용 태스크(`parityTest`, `perfTest`, `stressTest`, `parityKnownFailureTest`, `paritySnapshotTest`)로만
 * 도는 JUnit 태그.
 * 루트 build.gradle.kts의 태그 목록과 값이 같아야 한다.
 */
object TestTags {
    const val PARITY = "parity"
    const val PERF = "perf"
    const val STRESS = "stress"
    const val PARITY_KNOWN_FAILURE = "parity-known-failure"
    const val PARITY_SNAPSHOT = "parity-snapshot"
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

/**
 * parity가 찾은 실제 차이(버그)를 재현하는 시나리오. 고쳐지기 전까지 실패하므로 `parityTest`에서 빠지고
 * `./gradlew parityKnownFailureTest`로만 돈다. 고친 뒤에는 이 어노테이션을 [ParityTest]로 바꾼다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.PARITY_KNOWN_FAILURE)
annotation class ParityKnownFailure

/**
 * 시드 대신 외부 덤프(운영 스냅샷)를 넣어 도는 parity. `./gradlew paritySnapshotTest -Dparity.source.…`로만 돈다.
 * 입력 형식은 docs/parity.md "외부 데이터로 돌리기".
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.PARITY_SNAPSHOT)
annotation class ParitySnapshot
