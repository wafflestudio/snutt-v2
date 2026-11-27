package com.wafflestudio.snutt.core.test.stress

import java.nio.file.Path

/**
 * stress 테스트 규모. `./gradlew stressTest -PstressThreads=200 -PstressIterations=300`처럼 Gradle 프로퍼티로 바꾼다
 * (루트 build.gradle.kts가 시스템 프로퍼티로 넘긴다). 기본값은 로컬에서 stressTest 전체가 10분 안에 끝나는 크기다.
 */
data class StressSettings(
    /** 동시에 요청을 보내는 클라이언트 스레드 수. race 시나리오에서는 요청 수와 같다. */
    val threads: Int,
    /** 시나리오를 처음 상태부터 다시 돌리는 횟수. 반복마다 불변식을 검증한다. */
    val iterations: Int,
    /** 혼합 부하에서 스레드 하나가 반복 한 번에 보내는 요청 수. */
    val opsPerThread: Int,
    /** 요청 종류·대상 선택에 쓰는 난수 seed. 인터리빙은 스케줄러가 정하므로 같은 seed라도 결과 순서는 달라질 수 있다. */
    val seed: Long,
    val reportDir: Path,
) {
    companion object {
        fun fromSystemProperties(): StressSettings =
            StressSettings(
                threads = int("snutt.stress.threads", 50),
                iterations = int("snutt.stress.iterations", 10),
                opsPerThread = int("snutt.stress.opsPerThread", 20),
                seed = System.getProperty("snutt.stress.seed")?.toLong() ?: 20261128L,
                reportDir = Path.of(System.getProperty("snutt.stress.reportDir") ?: "build/reports/stress"),
            )

        private fun int(
            key: String,
            default: Int,
        ): Int = (System.getProperty(key)?.toInt() ?: default).also { require(it > 0) { "$key must be positive" } }
    }
}
