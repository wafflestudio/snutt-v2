package com.wafflestudio.snutt.migration.rehearsal

import com.wafflestudio.snutt.migration.rehearsal.mask.EvMasker
import com.wafflestudio.snutt.migration.rehearsal.mask.MaskReport
import com.wafflestudio.snutt.migration.rehearsal.mask.MaskingPlan
import com.wafflestudio.snutt.migration.rehearsal.mask.MongoMasker
import com.wafflestudio.snutt.migration.rehearsal.mask.Pseudonymizer
import org.bson.Document
import java.nio.file.Files
import java.nio.file.Path

class MaskOptions(
    /** 원본 `mongodump --archive --gzip --db <db>` 결과. */
    val mongoArchive: Path,
    /** 원본 `mysqldump --no-tablespaces <ev db>` 결과(.sql 또는 .sql.gz). */
    val evDump: Path,
    /** `mongo.archive.gz`, `ev.sql.gz`, `mask-report.md`를 쓴다. */
    val outDir: Path,
    /** HMAC 비밀값. 저장하지 않는다. 같은 비밀값이면 같은 결과가 나온다. */
    val secret: ByteArray,
    val mongoDatabase: String = "snutt",
    val dropUnknownCollections: Boolean = false,
)

/**
 * 덤프 마스킹: 원본 덤프를 로컬 컨테이너에 복원 → 제자리 마스킹 → 같은 형식으로 다시 덤프. 작업 DB는 끝나면 지운다.
 * 출력 Mongo 덤프의 DB 이름은 원본과 같다(parity의 `--nsFrom`이 그대로 맞도록).
 */
object DumpMasking {
    private const val EV_WORK = "mask_ev"

    fun run(options: MaskOptions): MaskReport {
        val pseudonymizer = Pseudonymizer(options.secret)
        val mongoDb = RehearsalContainers.mongoClient.getDatabase(options.mongoDatabase)
        try {
            RehearsalContainers.restoreMongo(options.mongoArchive, options.mongoDatabase, options.mongoDatabase)
            // 덤프 안 DB 이름이 다르면 빈 DB를 "마스킹"하고 빈 덤프를 쓰게 된다. 조용히 넘어가지 않는다.
            check(mongoDb.getCollection("users").countDocuments() > 0) {
                "복원한 Mongo ${options.mongoDatabase}에 users가 없다. --mongo-database(덤프 안 DB 이름)를 확인한다"
            }
            val report = MongoMasker(mongoDb, pseudonymizer, options.dropUnknownCollections).run()
            RehearsalContainers.restoreMysql(options.evDump, EV_WORK)
            val evTables =
                RehearsalContainers
                    .jdbc(EV_WORK)
                    .queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?", Long::class.java, EV_WORK)!!
            check(evTables > 0) { "EV 덤프를 넣었는데 $EV_WORK 가 비어 있다. --databases 없이 뜬 덤프인지 확인한다(USE 문이 있으면 다른 DB로 들어간다)" }
            EvMasker(RehearsalContainers.jdbc(EV_WORK), pseudonymizer, report).run()
            mongoDb
                .getCollection(MaskingPlan.MARKER_COLLECTION)
                .insertOne(Document("_id", "masked").append("plan", MaskingPlan.FIELDS.size).append("tool", "migration rehearsal maskDump"))

            Files.createDirectories(options.outDir)
            RehearsalContainers.dumpMongo(options.mongoDatabase, options.outDir.resolve(MONGO_FILE))
            RehearsalContainers.dumpMysql(EV_WORK, options.outDir.resolve(EV_FILE))
            Files.writeString(options.outDir.resolve("mask-report.md"), reportMarkdown(report))
            return report
        } finally {
            mongoDb.drop()
            RehearsalContainers.recreateDatabase(EV_WORK)
        }
    }

    const val MARKER = MaskingPlan.MARKER_COLLECTION
    const val MONGO_FILE = "mongo.archive.gz"
    const val EV_FILE = "ev.sql.gz"

    private fun reportMarkdown(report: MaskReport): String =
        buildString {
            appendLine("# 마스킹 리포트")
            appendLine()
            appendLine("규칙과 이유는 docs/migration-rehearsal.md \"마스킹 대상\". 대상 수: ${MaskingPlan.FIELDS.size}개 항목.")
            appendLine()
            append(report.toMarkdown())
        }
}
