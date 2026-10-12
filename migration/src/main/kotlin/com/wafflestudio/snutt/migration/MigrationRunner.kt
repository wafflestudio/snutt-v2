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
        val tables = steps.flatMap { it.tables }.distinct()
        if (truncate) {
            MigrationSupport.truncate(jdbc, tables.reversed())
        } else {
            MigrationSupport.requireEmpty(jdbc, tables)
        }

        log.info("이관 시작: {} (truncate={})", steps.joinToString { it.javaClass.simpleName }, truncate)
        val total =
            measureTimeMillis {
                steps.forEach { step ->
                    val elapsed = measureTimeMillis { step.run() }
                    log.info("[{}] 완료 ({} ms)", step.javaClass.simpleName, elapsed)
                }
            }
        log.info("이관 완료 ({} ms)", total)
    }
}
