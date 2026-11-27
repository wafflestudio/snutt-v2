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
        val tables = steps.flatMap { it.tables }
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
}
