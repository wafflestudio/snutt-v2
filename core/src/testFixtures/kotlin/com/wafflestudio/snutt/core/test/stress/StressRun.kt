package com.wafflestudio.snutt.core.test.stress

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * stress 시나리오 하나의 실행 기록. 요청 결과를 연산·결과별로 세고, 허용하지 않은 결과와 불변식 위반을 반복 번호와 함께 모은다.
 * [finish]가 리포트를 `build/reports/stress/<시나리오>.md`에 쓰고(실패해도 쓴다) 실패가 하나라도 있으면 AssertionError를 던진다.
 *
 * 데드락·lock wait timeout은 응답 코드와 상관없이 MySQL 누적 지표(INNODB_METRICS)의 차이로 반복마다 센다.
 * 서버 로그의 예외([ServerErrorLog])가 있으면 500의 원인 종류(데드락, 풀 타임아웃, unique 위반 등)로 나눈다.
 */
class StressRun(
    val scenario: String,
    val settings: StressSettings,
    private val environment: Map<String, String>,
    private val serverErrors: ServerErrorLog? = null,
) {
    class Failure(
        val iteration: Int,
        val kind: FailureKind,
        val operation: String,
        val detail: String,
    )

    private val outcomes = ConcurrentHashMap<Pair<String, String>, LongAdder>()
    private val failures = ConcurrentLinkedQueue<Failure>()
    private val failureCount = ConcurrentHashMap<FailureKind, LongAdder>()
    private val requests = LongAdder()
    private val deadlockSamples = ConcurrentLinkedQueue<String>()
    private val iterationsRun = AtomicInteger()
    private var deadlocks = 0L
    private var lockTimeouts = 0L
    private val startedAt = System.nanoTime()

    @Volatile
    var currentIteration: Int = 0
        private set

    /** 요청 하나의 결과. [outcome]은 "200", ErrorType 이름, "500"처럼 쓴다. [allowed]가 false면 실패로 센다. */
    fun record(
        operation: String,
        outcome: String,
        allowed: Boolean,
        detail: String = outcome,
    ) {
        requests.increment()
        outcomes.computeIfAbsent(operation to outcome) { LongAdder() }.increment()
        if (!allowed) {
            fail(if (outcome.startsWith("5")) FailureKind.SERVER_5XX else FailureKind.UNEXPECTED_OUTCOME, operation, detail)
        }
    }

    fun fail(
        kind: FailureKind,
        operation: String,
        detail: String,
    ) {
        failureCount.computeIfAbsent(kind) { LongAdder() }.increment()
        // 같은 원인이 수백 번 반복되면 리포트가 읽히지 않으므로 종류마다 앞의 몇 건만 자세히 남긴다.
        if (failures.count { it.kind == kind } < MAX_SAMPLES_PER_KIND) failures += Failure(currentIteration, kind, operation, detail)
    }

    /**
     * 반복 한 번을 돈다. [block] 안의 AssertionError(불변식 위반, 시나리오 verify 실패)는 실패로 기록하고 다음 반복으로 넘어간다.
     * 반복 전후의 InnoDB 지표 차이로 데드락·lock wait timeout을 센다.
     */
    fun iteration(
        index: Int,
        block: () -> Unit,
    ) {
        currentIteration = index
        val before = MySqlProbe.lockCounters()
        try {
            block()
        } catch (e: AssertionError) {
            fail(FailureKind.INVARIANT, "verify", e.message.orEmpty().take(MAX_DETAIL_LENGTH))
        }
        drainServerErrors()
        val delta = MySqlProbe.lockCounters() - before
        if (delta.deadlocks > 0) {
            deadlocks += delta.deadlocks
            fail(FailureKind.DEADLOCK, "innodb", "반복 $index 에서 데드락 ${delta.deadlocks}건")
            if (deadlockSamples.size < MAX_SAMPLES_PER_KIND) MySqlProbe.latestDeadlock()?.let(deadlockSamples::add)
        }
        if (delta.lockTimeouts > 0) {
            lockTimeouts += delta.lockTimeouts
            fail(FailureKind.LOCK_WAIT_TIMEOUT, "innodb", "반복 $index 에서 lock wait timeout ${delta.lockTimeouts}건")
        }
        iterationsRun.incrementAndGet()
    }

    /** 실패가 이만큼 쌓이면 남은 반복을 건너뛴다(이미 재현됐다). */
    fun shouldStop(): Boolean = failureCount.values.sumOf { it.sum() } >= STOP_AFTER_FAILURES

    private fun drainServerErrors() {
        serverErrors?.drain()?.forEach { entry ->
            fail(entry.kind, entry.logger.substringAfterLast('.'), FailureKind.summarize(entry.error))
        }
    }

    /** 리포트를 쓰고, 실패가 있으면 종류별 수와 함께 실패시킨다. */
    fun finish() {
        drainServerErrors()
        val report = render()
        Files.createDirectories(settings.reportDir)
        val file = settings.reportDir.resolve("$scenario.md")
        Files.writeString(file, report)
        Files.writeString(
            settings.reportDir.resolve("summary.md"),
            summaryLine() + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        println(summaryLine())
        if (failureCount.isNotEmpty()) {
            throw AssertionError(
                "stress '$scenario' 실패: " +
                    failureCount.entries.joinToString { "${it.key.label} ${it.value.sum()}" } +
                    " (리포트: ${file.toAbsolutePath()})\n" +
                    failures.take(5).joinToString("\n") { "  반복 ${it.iteration} ${it.kind.label} ${it.operation}: ${it.detail}" },
            )
        }
    }

    private fun elapsedSeconds(): Double = (System.nanoTime() - startedAt) / 1e9

    private fun summaryLine(): String {
        val total = requests.sum()
        val errors = failureCount.entries.joinToString(", ") { "${it.key.label} ${it.value.sum()}" }.ifEmpty { "없음" }
        return "| $scenario | 반복 ${iterationsRun.get()} | 스레드 ${settings.threads} | 요청 $total | " +
            "%.1f s | %.1f req/s | 데드락 $deadlocks | lock wait timeout $lockTimeouts | 실패: $errors |"
                .format(elapsedSeconds(), total / elapsedSeconds())
    }

    private fun render(): String =
        buildString {
            appendLine("# stress: $scenario")
            appendLine()
            appendLine("- 결과: ${if (failureCount.isEmpty()) "통과" else "실패"}")
            appendLine(
                "- 반복 ${iterationsRun.get()}/${settings.iterations}, 스레드 ${settings.threads}, " +
                    "스레드당 요청 ${settings.opsPerThread}, seed ${settings.seed}",
            )
            appendLine("- 요청 ${requests.sum()}건, %.1f초, %.1f req/s".format(elapsedSeconds(), requests.sum() / elapsedSeconds()))
            appendLine("- 데드락 $deadlocks, lock wait timeout $lockTimeouts (INNODB_METRICS 차이)")
            appendLine()
            appendLine("## 환경")
            environment.forEach { (key, value) -> appendLine("- $key: $value") }
            appendLine()
            appendLine("## 연산별 결과")
            appendLine("| 연산 | 결과 | 수 |")
            appendLine("|---|---|---|")
            outcomes.entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .forEach { appendLine("| ${it.key.first} | ${it.key.second} | ${it.value.sum()} |") }
            appendLine()
            appendLine("## 실패 종류별 수")
            if (failureCount.isEmpty()) appendLine("없음")
            failureCount.forEach { (kind, count) -> appendLine("- ${kind.label}: ${count.sum()}") }
            appendLine()
            appendLine("## 실패 표본 (종류마다 최대 $MAX_SAMPLES_PER_KIND 건)")
            failures.forEach { appendLine("- 반복 ${it.iteration} [${it.kind.label}] ${it.operation}: ${it.detail}") }
            if (deadlockSamples.isNotEmpty()) {
                appendLine()
                appendLine("## LATEST DETECTED DEADLOCK")
                deadlockSamples.forEach {
                    appendLine("```")
                    appendLine(it)
                    appendLine("```")
                }
            }
        }

    private companion object {
        const val MAX_SAMPLES_PER_KIND = 10
        const val MAX_DETAIL_LENGTH = 2_000
        const val STOP_AFTER_FAILURES = 50L
    }
}
