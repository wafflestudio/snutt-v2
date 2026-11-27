package com.wafflestudio.snutt.migration

import com.wafflestudio.snutt.migration.step.AggregateStep
import com.wafflestudio.snutt.migration.step.CatalogStep
import com.wafflestudio.snutt.migration.step.CourseStep
import com.wafflestudio.snutt.migration.step.EvLectureStep
import com.wafflestudio.snutt.migration.step.EvaluationStep
import com.wafflestudio.snutt.migration.step.LectureStep
import com.wafflestudio.snutt.migration.step.LegacySearchTagStep
import com.wafflestudio.snutt.migration.step.LegacyTokenStep
import com.wafflestudio.snutt.migration.step.NotificationStep
import com.wafflestudio.snutt.migration.step.ThemeStep
import com.wafflestudio.snutt.migration.step.TimetableStep
import com.wafflestudio.snutt.migration.step.UserDataStep
import com.wafflestudio.snutt.migration.step.UserStep
import com.wafflestudio.snutt.migration.step.ValidateStep
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import kotlin.system.measureTimeMillis

/**
 * 이관 순서: V5(`is_email_verified` NULL 허용)를 적용하고 null을 읽을 수 있는 새 api 파드를 먼저 배포한 뒤 이 도구를 실행한다.
 * 이 도구도 시작할 때 Flyway를 돌리지만, 예전 파드가 떠 있는 채로 null 계정을 넣으면 그 파드의 `User.isEmailVerified`(Boolean) 읽기가 깨진다.
 */
@Component
class MigrationRunner(
    private val jdbc: JdbcTemplate,
    catalog: CatalogStep,
    user: UserStep,
    course: CourseStep,
    lecture: LectureStep,
    evLecture: EvLectureStep,
    theme: ThemeStep,
    timetable: TimetableStep,
    userData: UserDataStep,
    notification: NotificationStep,
    evaluation: EvaluationStep,
    aggregate: AggregateStep,
    legacyToken: LegacyTokenStep,
    legacyTag: LegacySearchTagStep,
    validate: ValidateStep,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    private val steps =
        listOf(
            catalog,
            user,
            course,
            lecture,
            evLecture,
            theme,
            timetable,
            userData,
            notification,
            evaluation,
            aggregate,
            legacyToken,
            legacyTag,
            validate,
        )

    override fun run(args: ApplicationArguments) {
        require(args.nonOptionArgs.isEmpty()) { "부분 이관은 지원하지 않는다. --truncate 외의 인자는 받지 않는다" }
        val truncate = args.getOptionValues("truncate")?.let { it.singleOrNull()?.toBooleanStrictOrNull() ?: it.isEmpty() } ?: false
        migrate(truncate)
    }

    /**
     * 모든 단계를 순서대로 돌린다. 단계가 끝날 때마다(실패해도) [onStepDone]에 단계 이름과 소요 시간(ms)을 넘긴다.
     * 리허설 도구가 단계별 시간 리포트에 쓴다(중간 단계가 실패해도 그때까지의 시간이 남는다).
     */
    fun migrate(
        truncate: Boolean,
        onStepDone: (step: String, elapsedMs: Long) -> Unit = { _, _ -> },
    ) {
        val tables = targetTables(steps)
        if (truncate) {
            MigrationSupport.truncate(jdbc, tables)
        } else {
            MigrationSupport.requireEmpty(jdbc, tables)
        }

        log.info("이관 시작: {} (truncate={})", steps.joinToString { it.javaClass.simpleName }, truncate)
        val total =
            measureTimeMillis {
                steps.forEach { step ->
                    val start = System.nanoTime()
                    try {
                        step.run()
                    } finally {
                        onStepDone(step.javaClass.simpleName, (System.nanoTime() - start) / 1_000_000)
                    }
                    log.info("[{}] 완료", step.javaClass.simpleName)
                }
            }
        log.info("이관 완료 ({} ms)", total)
    }

    companion object {
        /**
         * 이관이 쓰지 않지만 이관이 매긴 id(강의·시간표·사용자)를 가리키는 v2 실행 상태(수강편람 동기화의 남은 일과 outbox, V6).
         * 외래 키가 없어서, 재이관 뒤 남아 있으면 새 id 체계의 엉뚱한 행에 적용된다. 그래서 이관 대상처럼 비우고 검사한다.
         */
        val SYNC_STATE_TABLES =
            listOf("sugang_sync_pending_lecture", "sugang_sync_pending_timetable", "sugang_sync_outbox", "sugang_sync_push_outbox")

        /**
         * `--truncate`가 비우지도, 첫 이관이 비어 있는지 보지도 않는 테이블과 그 이유. 새 테이블은 [targetTables]나 여기 중 한 곳에
         * 들어가야 한다(`MigrationIntegrationTest`가 information_schema와 맞춰 본다).
         */
        val UNMANAGED_TABLES =
            mapOf(
                "flyway_schema_history" to "Flyway 기록",
                "mail_log" to "v2가 보낸 메일 기록. 이메일·아이디 문자열만 담고 이관 id를 가리키지 않는다",
                "legacy_access_token" to "LegacyTokenStep이 직접 비우고 다시 채운다",
                "legacy_search_tag" to "LegacySearchTagStep이 직접 비우고 다시 채운다",
            )

        /** `--truncate`가 비우고, 첫 이관 전에 비어 있어야 하는 테이블. 단계가 쓰는 테이블과 [SYNC_STATE_TABLES]. */
        fun targetTables(steps: List<MigrationStep>): List<String> = (steps.flatMap { it.tables } + SYNC_STATE_TABLES).distinct()
    }
}
