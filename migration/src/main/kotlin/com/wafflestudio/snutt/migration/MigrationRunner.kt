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
        val truncate = args.containsOption("truncate")
        val requested = args.nonOptionArgs.filterNot { it == "all" }
        val byName = steps.associateBy { it.name }
        val selected =
            (if (requested.isEmpty()) ORDER else requested).map { name ->
                byName[name] ?: error("알 수 없는 단계: $name (가능한 값: ${ORDER.joinToString()})")
            }

        log.info("이관 시작: {} (truncate={})", selected.joinToString { it.name }, truncate)
        val total =
            measureTimeMillis {
                selected.forEach { step ->
                    if (truncate) {
                        MigrationSupport.truncate(jdbc, step.tables.reversed())
                    } else {
                        MigrationSupport.requireEmpty(jdbc, step.tables)
                    }
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
                "theme",
                "timetable",
                "reminder",
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
