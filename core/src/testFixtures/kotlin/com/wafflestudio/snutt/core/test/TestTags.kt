package com.wafflestudio.snutt.core.test

import org.junit.jupiter.api.Tag

/**
 * `./gradlew test`에서 제외되고 전용 태스크(`parityTest`, `perfTest`, `stressTest`, `liveTest`, `parityKnownFailureTest`,
 * `paritySnapshotTest`, `stressKnownFailureTest`, `rehearsalTest`)로만 도는 JUnit 태그.
 * 루트 build.gradle.kts의 태그 목록과 값이 같아야 한다.
 */
object TestTags {
    const val PARITY = "parity"
    const val PERF = "perf"
    const val STRESS = "stress"
    const val LIVE = "live"
    const val PARITY_KNOWN_FAILURE = "parity-known-failure"
    const val PARITY_SNAPSHOT = "parity-snapshot"
    const val REHEARSAL = "rehearsal"

    /** stress에서 재현된 버그(데드락, 정합성 깨짐). 고칠 때까지 `test`와 `stressTest` 모두에서 빠지고 `stressKnownFailureTest`로만 돈다. */
    const val STRESS_KNOWN_FAILURE = "stress-known-failure"
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

/**
 * stress가 재현한 버그를 고정한 테스트. 지금은 실패하는 것이 정상이며 `./gradlew stressKnownFailureTest`로만 돈다.
 * 버그를 고치면 이 어노테이션을 [StressTest]로 바꾼다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.STRESS_KNOWN_FAILURE)
annotation class StressKnownFailure

/**
 * 마이그레이션 리허설 도구(마스킹·복원·이관·검증)를 합성 덤프로 끝까지 돌리는 테스트. `./gradlew rehearsalTest`로만 돈다.
 * 런북은 docs/migration-rehearsal.md.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(TestTags.REHEARSAL)
annotation class RehearsalTest
