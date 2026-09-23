package com.wafflestudio.snutt.migration

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import kotlin.system.measureTimeMillis

@Component
class MigrationRunner(
    private val steps: List<MigrationStep>,
    private val jdbc: JdbcTemplate,
    private val context: MigrationContext,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        require(args.nonOptionArgs.isEmpty()) { "부분 이관은 지원하지 않는다. --truncate 외의 인자는 받지 않는다" }
        val truncate = args.getOptionValues("truncate")?.let { it.singleOrNull()?.toBooleanStrictOrNull() ?: it.isEmpty() } ?: false
        val byName = steps.associateBy { it.name }
        val selected = ORDER.map { name -> checkNotNull(byName[name]) { "이관 단계가 없다: $name" } }
        val tables = selected.flatMap { it.tables }.distinct()
        if (truncate) {
            MigrationSupport.truncate(jdbc, tables.reversed())
        } else {
            MigrationSupport.requireEmpty(jdbc, tables)
        }

        log.info("이관 시작: {} (truncate={})", selected.joinToString { it.name }, truncate)
        val total =
            measureTimeMillis {
                selected.forEach { step ->
                    val elapsed = measureTimeMillis { step.run() }
                    log.info("[{}] 완료 ({} ms)", step.name, elapsed)
                }
            }
        if (context.resolutions.isNotEmpty()) {
            log.warn("원본이 v2 제약을 위반해 손본 항목:")
            context.resolutions.forEach { (reason, count) -> log.warn("  - {}: {}건", reason, count) }
        }
        log.info("이관 완료 ({} ms)", total)
    }

    companion object {
        val ORDER =
            listOf(
                "catalog",
                "user",
                "course",
                "lecture",
                "evlecture",
                "theme",
                "timetable",
                "userdata",
                "notification",
                "evaluation",
                "aggregate",
                "legacytoken",
                "legacytag",
                "validate",
            )
    }
}
