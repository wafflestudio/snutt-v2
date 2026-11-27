package com.wafflestudio.snutt.migration.rehearsal

import com.wafflestudio.snutt.migration.EvSource
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationRunner
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.rehearsal.check.Checksums
import com.wafflestudio.snutt.migration.rehearsal.check.ExpectedCounts
import com.wafflestudio.snutt.migration.rehearsal.check.Fingerprint
import com.wafflestudio.snutt.migration.rehearsal.check.Invariants
import com.wafflestudio.snutt.migration.rehearsal.check.SampleComparison
import com.wafflestudio.snutt.migration.rehearsal.check.SourceIndex
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
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

class RehearsalOptions(
    /** `mongodump --archive --gzip --db <db>` 결과(마스킹본). */
    val mongoArchive: Path,
    /** `mysqldump --no-tablespaces <ev db>` 결과(.sql 또는 .sql.gz, 마스킹본). */
    val evDump: Path,
    val outDir: Path,
    /** 덤프 안의 Mongo DB 이름. */
    val mongoDatabase: String = "snutt",
    val sampleUsers: Int = 200,
    val sampleSeed: Long = DEFAULT_SEED,
    /** parity용 역할 파일(roles-sample.yml)에 넣을 사용자 수. */
    val parityUsers: Int = 50,
    /** 마스킹 표식이 없는 덤프(합성 데이터 등)도 받는다. 운영 원본에는 쓰지 않는다. */
    val allowUnmasked: Boolean = false,
) {
    companion object {
        const val DEFAULT_SEED = 20_261_128L
    }
}

/**
 * 마이그레이션 리허설: 덤프 복원 → 빈 v2 MySQL에 Flyway → 이관(실제 MigrationRunner) → 검증 → 같은 입력으로 다시 이관해 멱등성 확인.
 * 결과는 [RehearsalOptions.outDir]의 `rehearsal-report.md`·`.json`과 parity 표본 `roles-sample.yml`. 런북은 docs/migration-rehearsal.md.
 */
object Rehearsal {
    const val V1_MONGO = "rehearsal_v1"
    const val V1_EV = "rehearsal_v1_ev"
    const val V2 = "rehearsal_v2"

    private val json: JsonMapper = JsonMapper.builder().findAndAddModules().build()

    private val syncStateRows =
        listOf(
            "INSERT INTO sugang_sync_pending_lecture " +
                "(lecture_id, class_times_changed, changed_labels, changed_labels_en, notifiable, created_at) " +
                "VALUES (1, TRUE, '수업 시간', 'Class time', TRUE, NOW(6))",
            "INSERT INTO sugang_sync_pending_timetable (pending_lecture_id, timetable_id, notify) VALUES (1, 1, TRUE)",
            "INSERT INTO sugang_sync_outbox (user_id, title, message, type, push_kind, created_at) " +
                "VALUES (1, '수강편람 변경', '강의가 바뀌었습니다', 'LECTURE_UPDATE', 'UPDATED', NOW(6))",
            "INSERT INTO sugang_sync_push_outbox (user_id, updated, deleted) VALUES (1, 1, 0)",
        )

    fun run(options: RehearsalOptions): RehearsalReport {
        val report =
            RehearsalReport(
                mapOf(
                    "mongo-archive" to options.mongoArchive.toString(),
                    "mongo-database" to options.mongoDatabase,
                    "ev-dump" to options.evDump.toString(),
                    "sample-users" to options.sampleUsers.toString(),
                    "sample-seed" to options.sampleSeed.toString(),
                    "heap-max" to "${Runtime.getRuntime().maxMemory() / 1_048_576}MB",
                ),
            )
        val total = System.nanoTime()
        try {
            execute(options, report)
        } catch (e: Throwable) {
            // 준비 단계(복원·스키마)가 실패해도 그때까지의 시간과 원인을 리포트에 남긴다.
            report.checks += CheckResult("준비", "복원·스키마·색인", false, "예외 없음", e.toString().take(500))
        } finally {
            report.phases["전체"] = (System.nanoTime() - total) / 1_000_000
            report.write(options.outDir)
        }
        return report
    }

    /** 검증 하나가 예외로 끝나도 나머지 검증과 리포트는 계속한다(반쯤 쓰인 v2 DB에서도 원인을 남기려고). */
    private fun guarded(
        report: RehearsalReport,
        group: String,
        block: () -> Unit,
    ) {
        runCatching(block).exceptionOrNull()?.let {
            report.checks += CheckResult(group, "검증 실행", false, "예외 없음", it.toString().take(500))
        }
    }

    private fun execute(
        options: RehearsalOptions,
        report: RehearsalReport,
    ) {
        report.phase("복원: v1 Mongo") { RehearsalContainers.restoreMongo(options.mongoArchive, options.mongoDatabase, V1_MONGO) }
        report.phase("복원: v1 EV MySQL") { RehearsalContainers.restoreMysql(options.evDump, V1_EV) }
        val mongo = RehearsalContainers.mongoClient.getDatabase(V1_MONGO)
        check(mongo.getCollection("users").countDocuments() > 0) {
            "복원한 Mongo에 users가 없다. --mongo-database(덤프 안 DB 이름)를 확인한다"
        }
        val masked = mongo.getCollection(DumpMasking.MARKER).countDocuments() > 0
        check(masked || options.allowUnmasked) {
            "마스킹 표식이 없는 덤프다. maskDump 결과만 쓴다(리포트에 원본 값이 남는다). 합성 데이터처럼 원문이 아니면 --allow-unmasked"
        }
        report.phase("v2 스키마(Flyway)") {
            RehearsalContainers.recreateDatabase(V2)
            Flyway
                .configure()
                .dataSource(RehearsalContainers.jdbcUrl(V2), RehearsalContainers.ROOT, RehearsalContainers.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
        val v2 = RehearsalContainers.jdbc(V2)
        val source = report.phase("원본 색인") { SourceIndex(mongo, RehearsalContainers.jdbc(V1_EV)) }
        val expected = ExpectedCounts(source)
        val counts = report.phase("기대 건수 계산") { expected.compute() }

        val (firstResolutions, first) = firstRun(options, report, v2, source, expected, counts)
        report.phase("재실행 정책: 비우지 않고 다시 실행") {
            val (_, rerunError) = migrate(v2, truncate = false)
            val refused = rerunError is IllegalStateException && rerunError.message.orEmpty().contains("--truncate")
            report.checks +=
                CheckResult(
                    "멱등성·재실행",
                    "비우지 않은 재실행은 쓰기 전에 거부한다(부분·증분 이관 없음)",
                    refused,
                    "IllegalStateException(--truncate 안내)",
                    rerunError?.let { "${it.javaClass.simpleName}: ${it.message?.take(120)}" } ?: "거부하지 않음",
                )
        }
        // 1회차 뒤 v2가 돌며 남긴 수강편람 동기화 상태(외래 키 없음). 재이관이 비우지 않으면 새 id의 엉뚱한 강의·시간표·사용자에 적용된다.
        report.phase("재이관 전: 동기화 상태 남기기") { syncStateRows.forEach { v2.update(it) } }
        val (secondContext, secondError) = report.phase("이관 2회차(--truncate)") { migrate(v2, truncate = true) }
        val leftover = MigrationRunner.SYNC_STATE_TABLES.associateWith { v2.queryForObject("SELECT COUNT(*) FROM `$it`", Long::class.java) }
        report.checks +=
            CheckResult(
                "멱등성·재실행",
                "--truncate가 수강편람 동기화 상태(pending·outbox)를 비운다",
                leftover.values.all { it == 0L },
                "모두 0행",
                leftover.toString(),
            )
        val secondResolutions = LinkedHashMap(secondContext.resolutions)
        val second = report.phase("멱등성: 2회차 지문") { Fingerprint.of(v2) }
        report.checks +=
            CheckResult("멱등성·재실행", "2회차(--truncate) 실행", secondError == null, "예외 없음", secondError?.message ?: "예외 없음")
        report.checks +=
            CheckResult(
                "멱등성·재실행",
                "1·2회차 이관 정리 집계가 같다",
                firstResolutions == secondResolutions,
                firstResolutions.toString(),
                secondResolutions.toString(),
            )
        val differing = (first.keys + second.keys).filter { first[it]?.digest != second[it]?.digest || first[it]?.rows != second[it]?.rows }
        report.checks +=
            CheckResult(
                "멱등성·재실행",
                "1·2회차 테이블 지문(${first.size}개 테이블)",
                differing.isEmpty(),
                "모두 같음",
                if (differing.isEmpty()) "모두 같음" else "다름: $differing",
            )
    }

    /**
     * 이관 1회차와 그 결과 검증. 1회차 이관 컨텍스트(수백만 건의 id 표)는 이 함수 안에서만 살아 있어서,
     * 2회차가 운영처럼 컨텍스트 하나만 든 채로 돈다(소요 시간이 GC로 부풀지 않게).
     */
    private fun firstRun(
        options: RehearsalOptions,
        report: RehearsalReport,
        v2: JdbcTemplate,
        source: SourceIndex,
        expected: ExpectedCounts,
        counts: List<ExpectedCounts.Count>,
    ): Pair<Map<String, Long>, Map<String, Fingerprint.TableFingerprint>> {
        val (context, error) = report.phase("이관 1회차") { migrate(v2, truncate = false) { step, ms -> report.steps[step] = ms } }
        report.checks +=
            CheckResult(
                "이관",
                "1회차 실행과 ValidateStep(이관 자체 검증)",
                error == null,
                "예외 없음",
                error?.let { it.message ?: it.toString() } ?: "예외 없음",
            )
        report.resolutions.putAll(context.resolutions)

        report.phase("검증: 건수") {
            guarded(report, "건수 대조") {
                counts.forEach { count ->
                    val actual = v2.queryForObject(count.actualSql, Long::class.java) ?: 0L
                    report.counts += CountRow(count.label, count.source, count.expected, actual, count.rule)
                }
            }
        }
        report.phase("검증: 표본 대조") {
            guarded(report, "표본 대조") {
                val result = SampleComparison(source, v2, context).run(options.sampleUsers, options.sampleSeed)
                report.sampledUsers = result.users
                report.checks +=
                    CheckResult(
                        "표본 대조",
                        "사용자 ${result.users.size}명, 필드 ${result.comparedFields}개",
                        result.mismatches.isEmpty(),
                        "불일치 0",
                        "불일치 ${result.mismatches.size}",
                        result.mismatches.take(100).joinToString("\n"),
                    )
            }
        }
        report.phase("검증: 체크섬") {
            guarded(report, "체크섬(id 보존 테이블)") {
                Checksums(source, v2, context).run().forEach {
                    report.checks +=
                        CheckResult(
                            "체크섬(id 보존 테이블)",
                            it.name,
                            it.passed,
                            "${it.v1Rows}행 ${it.v1Digest.take(12)}",
                            "${it.v2Rows}행 ${it.v2Digest.take(12)}",
                            if (it.mismatchCount == 0L) "" else "어긋난 행 ${it.mismatchCount}개, id 예: ${it.mismatchedIds}",
                        )
                }
            }
        }
        report.phase("검증: 불변식") {
            guarded(report, "불변식") {
                Invariants(v2).check(expected.overlappingTimetables).forEach {
                    report.checks +=
                        CheckResult(
                            "불변식",
                            it.invariant.name,
                            it.passed,
                            "${it.expected}",
                            "${it.count}",
                            if (it.passed) "" else it.invariant.sql,
                        )
                }
            }
        }
        report.phase("parity 표본 역할 파일") { writeParityRoles(source, options) }
        val firstResolutions = LinkedHashMap(context.resolutions)

        val first = report.phase("멱등성: 1회차 지문") { Fingerprint.of(v2) }
        return firstResolutions to first
    }

    /**
     * parity 스냅샷 모드(`paritySnapshotTest`)가 읽는 역할 파일. 구 토큰이 이관되는(로그인 수단이 있고 토큰이 하나뿐인) 활성 사용자에서
     * seed로 뽑는다. docs/parity.md "외부 데이터로 돌리기".
     */
    private fun writeParityRoles(
        source: SourceIndex,
        options: RehearsalOptions,
    ) {
        val tokens =
            source.users.values
                .mapNotNull { it.legacyToken }
                .groupingBy { it }
                .eachCount()
        val eligible =
            source.users.values
                .filter { user -> user.legacyToken?.let { tokens[it] == 1 } == true }
                .map { it.hex }
        val picked = SampleComparison.sample(eligible, options.parityUsers, options.sampleSeed)
        val yaml =
            buildString {
                appendLine("# 리허설이 뽑은 parity 표본(seed ${options.sampleSeed}). 값은 마스킹된 스냅샷의 v1 users._id다.")
                appendLine("users:")
                picked.forEachIndexed { index, hex -> appendLine("  sample-${(index + 1).toString().padStart(3, '0')}: \"$hex\"") }
            }
        Files.createDirectories(options.outDir)
        Files.writeString(options.outDir.resolve("roles-sample.yml"), yaml)
    }

    /** MigrationRunner를 Spring 없이 조립해 실행한다(단계 순서·비우기 정책은 러너 그대로). 실패해도 그때까지의 결과를 돌려준다. */
    fun migrate(
        v2: JdbcTemplate,
        truncate: Boolean,
        mongoDatabase: String = V1_MONGO,
        evDatabase: String = V1_EV,
        onStepDone: (String, Long) -> Unit = { _, _ -> },
    ): Pair<MigrationContext, Throwable?> {
        val context = MigrationContext()
        val mongo = MongoSource(RehearsalContainers.mongoClient, mongoDatabase)
        val ev = EvSource(RehearsalContainers.jdbcUrl(evDatabase), RehearsalContainers.ROOT, RehearsalContainers.password)
        try {
            val runner =
                MigrationRunner(
                    v2,
                    CatalogStep(v2, context, mongo, json),
                    UserStep(v2, context, mongo),
                    CourseStep(v2, context, mongo, ev),
                    LectureStep(v2, context, mongo),
                    EvLectureStep(v2, context, ev),
                    ThemeStep(v2, context, mongo, json),
                    TimetableStep(v2, context, mongo, json),
                    UserDataStep(v2, context, mongo),
                    NotificationStep(v2, context, mongo),
                    EvaluationStep(v2, context, ev),
                    AggregateStep(v2, context),
                    LegacyTokenStep(v2, context),
                    LegacySearchTagStep(v2, context, ev),
                    ValidateStep(v2, context, mongo, ev),
                )
            val error = runCatching { runner.migrate(truncate, onStepDone) }.exceptionOrNull()
            return context to error
        } finally {
            ev.close()
        }
    }
}
