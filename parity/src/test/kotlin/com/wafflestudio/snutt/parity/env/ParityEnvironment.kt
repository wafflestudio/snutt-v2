package com.wafflestudio.snutt.parity.env

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.wafflestudio.snutt.migration.EvSource
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationRunner
import com.wafflestudio.snutt.migration.MigrationStep
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
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
import com.wafflestudio.snutt.parity.scenario.Side
import com.wafflestudio.snutt.parity.seed.MigrationResult
import com.wafflestudio.snutt.parity.seed.SeedAlias
import com.wafflestudio.snutt.parity.seed.SeedUser
import org.bson.Document
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper

/** v1 저장소. 데이터 소스는 비어 있는 이 저장소에 v1 데이터를 넣는다. */
class LegacyStores(
    val mongo: MongoDatabase,
    val ev: JdbcTemplate,
)

/** 데이터 소스가 넣은 데이터 중 시나리오가 이름으로 부를 수 있는 것. */
class LoadedLegacyData(
    val aliases: List<SeedAlias>,
    val users: Map<String, SeedUser>,
)

/**
 * parity 입력 데이터. 기본은 [com.wafflestudio.snutt.parity.seed.SeedDataSource](코드로 쓴 시드)이고,
 * 운영 스냅샷 리허설(작업 M)은 같은 인터페이스로 덤프를 넣는다. docs/parity.md "외부 데이터로 돌리기".
 */
interface ParityDataSource {
    val name: String

    fun load(stores: LegacyStores): LoadedLegacyData
}

/** 시나리오 하나가 쓰는 준비된 데이터: 이관 결과와 양쪽 별칭 표. */
class PreparedData(
    val migration: MigrationContext,
    /** 쪽별 `값 → 별칭들`. 응답 정규화가 id를 별칭으로 바꿀 때 쓴다. v2 id는 테이블마다 겹치므로 여러 개일 수 있다. */
    val knownIds: Map<Side, Map<String, List<String>>>,
    /** 쪽별 `별칭 → 값`. 요청의 `@{별칭}`을 채운다. `token:<사용자>`를 포함한다. */
    val vars: Map<Side, Map<String, String>>,
    val users: Map<String, SeedUser>,
    /** v1 저장소를 비우기 직전 시각. 이후의 시각 값(요청·이관 시각)은 형식만 비교한다. */
    val preparedSince: java.time.Instant,
)

object ParityEnvironment {
    private val jsonMapper: JsonMapper = JsonMapper.builder().findAndAddModules().build()

    val mongoClient: MongoClient by lazy { MongoClients.create(ParityContainers.mongoUri) }

    private val evSource: EvSource by lazy {
        EvSource(ParityContainers.evJdbcUrl, ParityContainers.MYSQL_ROOT_USER, ParityContainers.mysqlRootPassword)
    }

    private val v1Redis: LettuceConnectionFactory by lazy {
        LettuceConnectionFactory(
            RedisStandaloneConfiguration(ParityContainers.v1Redis.host, ParityContainers.v1Redis.getMappedPort(6379)),
        ).apply { afterPropertiesSet() }
    }

    /**
     * v1 저장소를 비우고 [source]의 데이터를 넣은 뒤, 실제 migration 단계로 v2 DB에 옮긴다.
     * v2 테이블과 v2 Redis는 [com.wafflestudio.snutt.core.test.AbstractIntegrationTest]가 매 테스트 전에 비운다.
     */
    fun prepare(
        source: ParityDataSource,
        v2: JdbcTemplate,
    ): PreparedData {
        ParityContainers.start()
        // 컨테이너 시계 차이를 감안해 1분 앞당긴다.
        val preparedSince =
            java.time.Instant
                .now()
                .minusSeconds(60)
        resetV1()
        val loaded = source.load(LegacyStores(mongoClient.getDatabase(ParityContainers.MONGO_DATABASE), evSource.jdbc))
        exportSeedOnce(source, loaded)
        val context = migrate(v2)
        val result = MigrationResult(context, v2)

        val v1Values = LinkedHashMap<String, String>()
        val v2Values = LinkedHashMap<String, String>()
        loaded.aliases.forEach { alias ->
            v1Values[alias.name] = alias.v1
            alias.v2(result)?.let { v2Values[alias.name] = it }
        }
        loaded.users.values.forEach { user ->
            v1Values["token:${user.alias}"] = user.token
            v2Values["token:${user.alias}"] = user.token
        }
        return PreparedData(
            migration = context,
            knownIds =
                mapOf(
                    Side.V1 to reverse(v1Values),
                    Side.V2 to reverse(v2Values),
                ),
            vars = mapOf(Side.V1 to v1Values, Side.V2 to v2Values),
            users = loaded.users,
            preparedSince = preparedSince,
        )
    }

    private var exported = false

    /**
     * `-Dparity.dump-seed-to=<dir>`이면 처음 넣은 시드를 [SnapshotDataSource] 입력 형식(mongo.archive.gz, ev.sql, roles.yml)으로
     * 내보낸다. 스냅샷 경로를 시드로 검증하거나 덤프 형식을 확인할 때 쓴다.
     */
    private fun exportSeedOnce(
        source: ParityDataSource,
        loaded: LoadedLegacyData,
    ) {
        val target = System.getProperty("parity.dump-seed-to") ?: return
        if (exported || source.name != "seed") return
        exported = true
        val dir =
            java.nio.file.Paths
                .get(target)
        ParityContainers.dumpTo(dir)
        val roles =
            buildString {
                appendLine("# parity 시드에서 내보낸 역할 파일. 운영 스냅샷용은 같은 형식으로 v1 users._id를 적는다.")
                appendLine("users:")
                loaded.users.values.filter { it.token != "parity-token-shared" }.forEach {
                    appendLine(
                        "  ${it.alias}: \"${it.id.toHexString()}\"",
                    )
                }
            }
        java.nio.file.Files
            .writeString(dir.resolve("roles.yml"), roles)
    }

    /** 등록 순서를 지킨다(이관이 합친 강의처럼 같은 값을 여러 별칭이 가지면 먼저 등록된 별칭이 앞). 토큰은 id가 아니므로 뺀다. */
    private fun reverse(values: Map<String, String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        values.entries.filterNot { it.key.startsWith("token:") }.forEach { (alias, value) ->
            out.getOrPut(value) { mutableListOf() } +=
                alias
        }
        return out
    }

    private fun resetV1() {
        val db = mongoClient.getDatabase(ParityContainers.MONGO_DATABASE)
        // drop하면 v1이 기동할 때 만든 인덱스(유일 제약 포함)가 사라진다. 문서만 지운다.
        db.listCollectionNames().forEach { db.getCollection(it).deleteMany(Document()) }
        val ev = evSource.jdbc
        val tables =
            ev.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'",
                String::class.java,
            )
        ev.execute(
            ConnectionCallback { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET FOREIGN_KEY_CHECKS = 0")
                    tables.filter { it != "flyway_schema_history" }.forEach { statement.execute("TRUNCATE TABLE `$it`") }
                    statement.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            },
        )
        v1Redis.connection.use { it.serverCommands().flushAll() }
    }

    /** migration 모듈의 MigrationRunner와 같은 순서로 같은 단계를 돈다(Spring 없이 직접 조립). */
    private fun migrate(target: JdbcTemplate): MigrationContext {
        val context = MigrationContext()
        val mongo = MongoSource(mongoClient, ParityContainers.MONGO_DATABASE)
        val ev = evSource
        val steps =
            listOf<MigrationStep>(
                CatalogStep(target, context, mongo, jsonMapper),
                UserStep(target, context, mongo),
                CourseStep(target, context, mongo, ev),
                LectureStep(target, context, mongo),
                EvLectureStep(target, context, ev),
                ThemeStep(target, context, mongo, jsonMapper),
                TimetableStep(target, context, mongo, jsonMapper),
                UserDataStep(target, context, mongo),
                NotificationStep(target, context, mongo),
                EvaluationStep(target, context, ev),
                AggregateStep(target, context),
                LegacyTokenStep(target, context),
                LegacySearchTagStep(target, context, ev),
                ValidateStep(target, context, mongo, ev),
            )
        MigrationSupport.requireEmpty(target, MigrationRunner.targetTables(steps))
        steps.forEach { it.run() }
        return context
    }
}
