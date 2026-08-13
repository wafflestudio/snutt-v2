package com.wafflestudio.snutt.migration

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

// 단계별 CLI: 인자로 "all" 또는 단계 이름 목록("users lectures timetables ...")을 받는다 (PLAN.md §5 순서)
@Component
class MigrationRunner(
    private val steps: List<MigrationStep>,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val requested = args.nonOptionArgs
        val stepMap = steps.associateBy { it.name }
        val selected =
            if (requested.isEmpty() || requested.contains("all")) {
                ORDER.map { stepMap[it] ?: error("unknown step: $it") }
            } else {
                requested.map { stepMap[it] ?: error("unknown step: $it") }
            }
        log.info("=== 마이그레이션 시작: {}", selected.joinToString { it.name })
        selected.forEach { step ->
            log.info("--- step {} 시작", step.name)
            step.run()
            log.info("--- step {} 완료", step.name)
        }
        log.info("=== 마이그레이션 완료")
    }

    companion object {
        // PLAN.md §5 순서
        private val ORDER =
            listOf(
                "users",
                "course",
                "lecture",
                "timetable",
                "bookmark",
                "theme",
                "friend",
                "misc",
                "evaluation",
                "validate",
            )
    }
}

interface MigrationStep {
    val name: String

    fun run()
}
