package com.wafflestudio.snutt.parity.env

import com.mongodb.client.model.Filters
import com.wafflestudio.snutt.parity.seed.MigrationResult
import com.wafflestudio.snutt.parity.seed.SeedAlias
import com.wafflestudio.snutt.parity.seed.SeedUser
import org.bson.types.ObjectId
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 외부 덤프(운영 스냅샷)를 v1 저장소에 넣는 데이터 소스. 작업 M(마이그레이션 리허설)이 시드 대신 쓴다.
 *
 * - [mongoArchive]: `mongodump --archive --gzip --db <db>` 결과. [mongoDatabase]가 덤프의 DB 이름이다(기본 snutt).
 * - [evDump]: `mysqldump --no-tablespaces <ev db>` 결과(.sql 또는 .sql.gz). `--databases` 없이 뜬 덤프여야 한다.
 * - [roles]: 시나리오가 쓸 사용자. `users: {alice: <v1 users._id>}` 형식. 토큰은 덤프의 credentialHash를 쓴다.
 *   `aliases: {tt:alice-main: <timetables._id>}`로 다른 별칭도 줄 수 있다(종류: user, lecture, tt, theme, course, evaluation).
 */
class SnapshotDataSource(
    private val mongoArchive: Path,
    private val evDump: Path,
    private val roles: Path,
    private val mongoDatabase: String = ParityContainers.MONGO_DATABASE,
) : ParityDataSource {
    override val name = "snapshot"

    class Roles(
        val users: Map<String, String> = emptyMap(),
        val aliases: Map<String, String> = emptyMap(),
    )

    override fun load(stores: LegacyStores): LoadedLegacyData {
        ParityContainers.restoreMongo(mongoArchive, mongoDatabase)
        ParityContainers.restoreEv(evDump)
        val parsed: Roles = yaml.readValue(Files.readString(roles))
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
        private val yaml = YAMLMapper.builder().addModule(KotlinModule.Builder().build()).build()

        /** `-Dparity.source.mongo-archive=… -Dparity.source.ev-dump=… -Dparity.source.roles=…`. 하나라도 없으면 실패한다. */
        fun fromSystemProperties(): SnapshotDataSource {
            fun path(key: String): Path {
                val value = System.getProperty(key) ?: error("$key 가 없다. docs/parity.md '외부 데이터로 돌리기'를 본다")
                return Paths.get(value).also { check(Files.isRegularFile(it)) { "$key=$value 파일이 없다" } }
            }
            return SnapshotDataSource(
                path("parity.source.mongo-archive"),
                path("parity.source.ev-dump"),
                path("parity.source.roles"),
                System.getProperty("parity.source.mongo-database") ?: ParityContainers.MONGO_DATABASE,
            )
        }
    }
}
