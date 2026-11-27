package com.wafflestudio.snutt.migration.rehearsal

import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** 검증 항목 하나. [passed]가 false인 항목이 하나라도 있으면 리허설은 실패다. */
class CheckResult(
    val group: String,
    val name: String,
    val passed: Boolean,
    val expected: String,
    val actual: String,
    val detail: String = "",
)

class CountRow(
    val label: String,
    val source: Long,
    val expected: Long,
    val actual: Long,
    val rule: String,
) {
    val passed get() = expected == actual
}

class RehearsalReport(
    val input: Map<String, String>,
) {
    val phases = LinkedHashMap<String, Long>()
    val steps = LinkedHashMap<String, Long>()
    val counts = mutableListOf<CountRow>()
    val checks = mutableListOf<CheckResult>()
    val resolutions = LinkedHashMap<String, Long>()
    var sampledUsers: List<String> = emptyList()

    val passed get() = checks.all { it.passed } && counts.all { it.passed }

    val failures: List<String>
        get() =
            counts.filterNot { it.passed }.map { "건수 ${it.label}: 기대 ${it.expected}, 실제 ${it.actual}" } +
                checks.filterNot { it.passed }.map { "${it.group} / ${it.name}: 기대 ${it.expected}, 실제 ${it.actual} ${it.detail}".trim() }

    fun <T> phase(
        name: String,
        block: () -> T,
    ): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            phases[name] = (System.nanoTime() - start) / 1_000_000
        }
    }

    fun write(dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("rehearsal-report.md"), toMarkdown())
        Files.writeString(dir.resolve("rehearsal-report.json"), toJson())
    }

    fun toMarkdown(): String =
        buildString {
            appendLine("# 마이그레이션 리허설 리포트")
            appendLine()
            appendLine("판정: **${if (passed) "통과" else "실패"}**")
            appendLine()
            appendLine("## 입력")
            input.forEach { (k, v) -> appendLine("- $k: `$v`") }
            appendLine()
            appendLine("## 소요 시간")
            appendLine("| 단계 | ms |")
            appendLine("|---|---:|")
            phases.forEach { (k, v) -> appendLine("| $k | $v |") }
            appendLine()
            appendLine("### 이관 단계별(1회차)")
            appendLine("| 단계 | ms |")
            appendLine("|---|---:|")
            steps.forEach { (k, v) -> appendLine("| $k | $v |") }
            appendLine()
            appendLine("## 건수 대조")
            appendLine("기대값은 v1 원본에서 이관 규칙으로 따로 계산한 값이다(ExpectedCounts).")
            appendLine()
            appendLine("| 대상 | v1 원본 | 기대 | v2 실제 | 판정 | 원본과 다른 이유 |")
            appendLine("|---|---:|---:|---:|---|---|")
            counts.forEach {
                appendLine(
                    "| ${it.label} | ${it.source} | ${it.expected} | ${it.actual} | ${if (it.passed) "OK" else "**불일치**"} | ${it.rule} |",
                )
            }
            appendLine()
            appendLine("## 검증 항목")
            checks.groupBy { it.group }.forEach { (group, items) ->
                appendLine("### $group")
                appendLine("| 항목 | 기대 | 실제 | 판정 |")
                appendLine("|---|---|---|---|")
                items.forEach {
                    appendLine("| ${it.name} | ${it.expected} | ${it.actual} | ${if (it.passed) "OK" else "**실패**"} |")
                }
                items.filter { it.detail.isNotBlank() }.forEach {
                    appendLine()
                    appendLine("<details><summary>${it.name}</summary>")
                    appendLine()
                    appendLine("```")
                    appendLine(it.detail)
                    appendLine("```")
                    appendLine("</details>")
                }
                appendLine()
            }
            appendLine("## 이관이 손본 항목(MigrationContext.resolutions)")
            if (resolutions.isEmpty()) appendLine("없음")
            resolutions.forEach { (k, v) -> appendLine("- $k: ${v}건") }
            if (!passed) {
                appendLine()
                appendLine("## 실패 목록")
                failures.forEach { appendLine("- $it") }
            }
        }

    fun toJson(): String =
        JsonMapper
            .builder()
            .build()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                mapOf(
                    "passed" to passed,
                    "input" to input,
                    "phasesMs" to phases,
                    "stepsMs" to steps,
                    "counts" to
                        counts.map {
                            mapOf(
                                "label" to it.label,
                                "source" to it.source,
                                "expected" to it.expected,
                                "actual" to it.actual,
                                "passed" to it.passed,
                            )
                        },
                    "checks" to
                        checks.map {
                            mapOf(
                                "group" to it.group,
                                "name" to it.name,
                                "passed" to it.passed,
                                "expected" to it.expected,
                                "actual" to it.actual,
                            )
                        },
                    "resolutions" to resolutions,
                    "failures" to failures,
                ),
            )
}
