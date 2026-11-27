package com.wafflestudio.snutt.parity.env

import com.mongodb.client.model.Filters
import com.wafflestudio.snutt.parity.seed.MigrationResult
import com.wafflestudio.snutt.parity.seed.SeedAlias
import com.wafflestudio.snutt.parity.seed.SeedUser
import org.bson.Document
import org.bson.types.ObjectId
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random

/**
 * 외부 덤프(운영 스냅샷)를 v1 저장소에 넣는 데이터 소스. 작업 M(마이그레이션 리허설)이 시드 대신 쓴다.
 *
 * - [mongoArchive]: `mongodump --archive --gzip --db <db>` 결과. [mongoDatabase]가 덤프의 DB 이름이다(기본 snutt).
 * - [evDump]: `mysqldump --no-tablespaces <ev db>` 결과(.sql 또는 .sql.gz). `--databases` 없이 뜬 덤프여야 한다.
 * - [roles]: 시나리오가 쓸 사용자. `users: {alice: <v1 users._id>}` 형식. 토큰은 덤프의 credentialHash를 쓴다.
 *   `aliases: {tt:alice-main: <timetables._id>}`로 다른 별칭도 줄 수 있다(종류: user, lecture, tt, theme, course, evaluation).
 *   마이그레이션 리허설(`./gradlew :migration:rehearse`)이 같은 형식의 표본 파일 `roles-sample.yml`을 만든다.
 * - [sampleUsers]: 0보다 크면 덤프에서 사용자를 [sampleSeed]로 그만큼 뽑아(`rand-001`…, roles에 이미 있는 사용자는 빼고) roles에 더한다. 구 토큰이 이관되는
 *   사용자(활성, 로그인 수단 있음, 토큰을 다른 활성 계정과 공유하지 않음)만 뽑는다. 읽기 시나리오를 대량으로 돌릴 때 쓴다.
 */
class SnapshotDataSource(
    private val mongoArchive: Path,
    private val evDump: Path,
    private val roles: Path?,
    private val mongoDatabase: String = ParityContainers.MONGO_DATABASE,
    private val sampleUsers: Int = 0,
    private val sampleSeed: Long = DEFAULT_SAMPLE_SEED,
) : ParityDataSource {
    override val name = "snapshot"

    class Roles(
        val users: Map<String, String> = emptyMap(),
        val aliases: Map<String, String> = emptyMap(),
    )

    override fun load(stores: LegacyStores): LoadedLegacyData {
        ParityContainers.restoreMongo(mongoArchive, mongoDatabase)
        ParityContainers.restoreEv(evDump)
        val listed: Roles = roles?.let { yaml.readValue(Files.readString(it)) } ?: Roles()
        val parsed = Roles(listed.users + sample(stores, listed.users.values.toSet()), listed.aliases)
        check(parsed.users.isNotEmpty()) { "비교할 사용자가 없다. parity.source.roles나 parity.source.sample-users를 준다" }
        val users =
            parsed.users.mapValues { (alias, hex) ->
                val document =
                    checkNotNull(
                        stores.mongo
                            .getCollection("users")
                            .find(Filters.eq("_id", ObjectId(hex)))
                            .first(),
                    ) {
                        "roles의 사용자 $alias($hex)가 덤프에 없다"
                    }
                SeedUser(alias, ObjectId(hex), document.getString("credentialHash"), null, null)
            }
        val aliases =
            parsed.users.map { (alias, hex) -> alias("user:$alias", hex) } +
                parsed.aliases.map { (name, value) -> alias(name, value) }
        return LoadedLegacyData(aliases, users)
    }

    private fun sample(
        stores: LegacyStores,
        listed: Set<String>,
    ): Map<String, String> {
        if (sampleUsers <= 0) return emptyMap()
        val candidates = mutableListOf<Pair<String, String>>()
        stores.mongo.getCollection("users").find(Filters.eq("active", true)).forEach { doc ->
            val credential = doc["credential"] as? Document ?: return@forEach
            val token = doc["credentialHash"] as? String
            if (token.isNullOrBlank() || LOGIN_KEYS.none { credential[it] is String }) return@forEach
            candidates += doc.getObjectId("_id").toHexString() to token
        }
        val tokenCounts = candidates.groupingBy { it.second }.eachCount()
        return candidates
            .filter { (hex, token) -> tokenCounts[token] == 1 && hex !in listed }
            .map { it.first }
            .sorted()
            .shuffled(Random(sampleSeed))
            .take(sampleUsers)
            .mapIndexed { index, hex -> "rand-${(index + 1).toString().padStart(3, '0')}" to hex }
            .toMap()
    }

    private fun alias(
        name: String,
        v1: String,
    ): SeedAlias =
        SeedAlias(name, v1) { result: MigrationResult ->
            val context = result.context
            when (name.substringBefore(':')) {
                "user" -> context.userIds[v1]
                "lecture" -> context.lectureIds[v1]
                "tt" -> context.timetableIds[v1]
                "theme" -> context.themeIds[v1]
                "course" -> v1.toLong().let { context.courseIdRemap[it] ?: it }
                "evaluation" -> v1.toLong()
                else -> error("지원하지 않는 별칭 종류: $name (user, lecture, tt, theme, course, evaluation)")
            }?.toString()
        }

    companion object {
        const val DEFAULT_SAMPLE_SEED = 20_261_128L
        private val LOGIN_KEYS = listOf("localId", "fbId", "appleSub", "googleSub", "kakaoSub")
        private val yaml = YAMLMapper.builder().addModule(KotlinModule.Builder().build()).build()

        /**
         * `-Dparity.source.mongo-archive=… -Dparity.source.ev-dump=…`와 `-Dparity.source.roles=…`·`-Dparity.source.sample-users=N` 중
         * 하나 이상(`-Dparity.source.sample-seed`는 선택). 덤프가 없거나 사용자를 정하지 않으면 실패한다.
         */
        fun fromSystemProperties(): SnapshotDataSource {
            fun path(key: String): Path? {
                val value = System.getProperty(key) ?: return null
                return Paths.get(value).also { check(Files.isRegularFile(it)) { "$key=$value 파일이 없다" } }
            }

            fun required(key: String): Path = path(key) ?: error("$key 가 없다. docs/parity.md '외부 데이터로 돌리기'를 본다")
            val sampleUsers = System.getProperty("parity.source.sample-users")?.toInt() ?: 0
            val roles = path("parity.source.roles")
            check(roles != null || sampleUsers > 0) { "parity.source.roles나 parity.source.sample-users 중 하나가 필요하다" }
            return SnapshotDataSource(
                required("parity.source.mongo-archive"),
                required("parity.source.ev-dump"),
                roles,
                System.getProperty("parity.source.mongo-database") ?: ParityContainers.MONGO_DATABASE,
                sampleUsers,
                System.getProperty("parity.source.sample-seed")?.toLong() ?: DEFAULT_SAMPLE_SEED,
            )
        }
    }
}
