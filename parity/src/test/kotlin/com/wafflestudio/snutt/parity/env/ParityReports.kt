package com.wafflestudio.snutt.parity.env

import com.wafflestudio.snutt.parity.compare.AllowedDiffs
import com.wafflestudio.snutt.parity.compare.JsonBodies
import com.wafflestudio.snutt.parity.scenario.ParityScenario
import com.wafflestudio.snutt.parity.scenario.Side
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 사람이 읽는 diff 리포트. 시나리오마다 `<report-dir>/scenarios/<이름>.md`(요청, 양쪽 응답, 차이 경로)를 쓰고,
 * `<report-dir>/summary.md`에 시나리오·엔드포인트·허용 차이 집계를 갱신한다. report-dir 기본값은 `parity/build/reports/parity`.
 */
object ParityReports {
    val dir: Path = Paths.get(System.getProperty("parity.report-dir") ?: "build/reports/parity")

    private val results = LinkedHashMap<String, ScenarioResult>()

    init {
        // 이전 실행의 시나리오 리포트가 섞이지 않게 JVM마다 한 번 비운다.
        dir.resolve("scenarios").toFile().deleteRecursively()
    }

    private class ScenarioResult(
        val name: String,
        val exchanges: Int,
        val endpoints: Set<String>,
        val allowed: Int,
        val known: Int,
        val unexpected: Int,
    )

    fun fileOf(scenario: ParityScenario): Path = dir.resolve("scenarios").resolve(fileName(scenario.name))

    @Synchronized
    fun write(scenario: ParityScenario) {
        val file = fileOf(scenario)
        Files.createDirectories(file.parent)
        Files.writeString(file, render(scenario))
        results[scenario.name] =
            ScenarioResult(
                scenario.name,
                scenario.exchanges.size,
                scenario.exchanges.map { it.endpoint }.toSet(),
                scenario.exchanges.sumOf { e -> e.diffs.count { it.allowed != null } },
                scenario.exchanges.sumOf { e -> e.diffs.count { it.allowed == null && it.knownFailure != null } },
                scenario.exchanges.sumOf { it.unexpected.size },
            )
    }

    @Synchronized
    fun writeSummary(allowed: AllowedDiffs) {
        Files.createDirectories(dir)
        val text =
            buildString {
                appendLine("# parity 요약")
                appendLine()
                appendLine("| 항목 | 값 |")
                appendLine("|---|---|")
                appendLine("| 시나리오 | ${results.size} (실패 ${results.values.count { it.unexpected > 0 }}) |")
                appendLine("| 요청(교환) | ${results.values.sumOf { it.exchanges }} |")
                appendLine("| 비교한 엔드포인트 | ${results.values.flatMap { it.endpoints }.toSet().size} |")
                appendLine("| 허용 목록에 걸린 차이 | ${results.values.sumOf { it.allowed }} |")
                appendLine("| 알려진 버그로 표시된 차이(known-failures.yml) | ${results.values.sumOf { it.known }} |")
                appendLine("| 허용 목록에 없는 차이 | ${results.values.sumOf { it.unexpected }} |")
                appendLine("| 허용 목록 항목(전체/이번 실행에서 안 쓰임) | ${allowed.entries.size}/${allowed.unused().size} |")
                appendLine()
                appendLine("| 시나리오 | 요청 | 허용 차이 | 알려진 버그 | 실패 차이 |")
                appendLine("|---|---|---|---|---|")
                results.values.forEach {
                    appendLine(
                        "| [${it.name}](scenarios/${fileName(
                            it.name,
                        )}) | ${it.exchanges} | ${it.allowed} | ${it.known} | ${it.unexpected} |",
                    )
                }
                appendLine()
                appendLine("## 비교한 엔드포인트")
                results.values
                    .flatMap { it.endpoints }
                    .toSortedSet()
                    .forEach { appendLine("- `$it`") }
                val unused = allowed.unused()
                if (unused.isNotEmpty()) {
                    appendLine()
                    appendLine("## 이번 실행에서 쓰이지 않은 허용 항목")
                    appendLine("고쳐졌거나 시나리오가 빠졌다는 뜻이다. 둘 다 아니면 항목 경로가 틀렸다.")
                    unused.forEach { appendLine("- `${it.endpoint}` `${it.path}` — ${it.reason}") }
                }
            }
        Files.writeString(dir.resolve("summary.md"), text)
    }

    private fun render(scenario: ParityScenario): String =
        buildString {
            appendLine("# ${scenario.name}")
            appendLine()
            scenario.exchanges.forEachIndexed { index, exchange ->
                val mark = if (exchange.unexpected.isEmpty()) "OK" else "DIFF"
                appendLine("## ${index + 1}. [$mark] ${exchange.label}")
                appendLine()
                Side.entries.forEach { side ->
                    val request = exchange.requests.getValue(side)
                    val response = exchange.responses.getValue(side)
                    appendLine("**${side.name}** `${request.method} ${request.path}` → ${response.status}")
                    request.body?.let { appendLine("요청 바디: `${it.take(500)}`") }
                    appendLine()
                    appendLine("```json")
                    appendLine(pretty(exchange.bodies[side]).take(BODY_LIMIT))
                    appendLine("```")
                }
                if (exchange.diffs.isNotEmpty()) {
                    appendLine("차이:")
                    exchange.diffs.forEach { verdict ->
                        val tag =
                            verdict.allowed?.let { "허용 — ${it.reason}" }
                                ?: verdict.knownFailure?.let { "알려진 버그 ${it.ref.orEmpty()} — ${it.reason}" }
                                ?: "**미등록**"
                        appendLine("- `${verdict.diff.describe()}` ($tag)")
                    }
                }
                appendLine()
            }
        }

    private fun pretty(body: Any?): String = if (body is String) body else JsonBodies.pretty(body)

    private fun fileName(name: String) = name.replace(Regex("[^0-9A-Za-z가-힣._-]+"), "_") + ".md"

    private const val BODY_LIMIT = 6_000
}
