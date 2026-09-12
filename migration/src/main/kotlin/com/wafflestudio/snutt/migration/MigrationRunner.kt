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
        require(args.nonOptionArgs.isEmpty() || args.nonOptionArgs == listOf("all")) {
            "ID mapping은 실행 중에만 유지되므로 부분 이관은 지원하지 않는다. 인자 없이 또는 all로 전체 이관을 실행한다"
        }
        val truncateValues = args.getOptionValues("truncate").orEmpty()
        require(truncateValues.size <= 1) { "--truncate는 한 번만 지정한다" }
        val truncate =
            if (!args.containsOption("truncate")) {
                false
            } else {
                truncateValues.singleOrNull()?.toBooleanStrictOrNull()
                    ?: if (truncateValues.isEmpty()) true else error("--truncate 값은 true 또는 false여야 한다")
            }
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
                "coursesemester",
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
