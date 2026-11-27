package com.wafflestudio.snutt.migration.rehearsal

import com.mongodb.client.MongoDatabase
import com.wafflestudio.snutt.core.test.RehearsalTest
import com.wafflestudio.snutt.migration.rehearsal.mask.MaskingPlan
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * 리허설 도구(마스킹 → 복원 → Flyway → 이관 → 검증 → 재이관)가 작은 합성 덤프로 끝까지 도는지 본다.
 * 운영 스냅샷은 쓰지 않는다. 실행: `./gradlew :migration:rehearsalTest`. 런북은 docs/migration-rehearsal.md.
 */
@RehearsalTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationRehearsalTest {
    private val work: Path = Paths.get("build", "rehearsal").toAbsolutePath()
    private val raw = work.resolve("raw")
    private val secret = "rehearsal-test-secret-0123456789".toByteArray()
    private lateinit var data: SyntheticLegacyData

    @BeforeAll
    fun generateSyntheticDump() {
        work.toFile().deleteRecursively()
        data = SyntheticLegacyData(seed = 7)
        val mongo = RehearsalContainers.mongoClient.getDatabase(SOURCE_DB)
        mongo.drop()
        RehearsalContainers.recreateDatabase(SOURCE_EV)
        data.write(mongo, RehearsalContainers.jdbc(SOURCE_EV))
        RehearsalContainers.dumpMongo(SOURCE_DB, raw.resolve(DumpMasking.MONGO_FILE))
        RehearsalContainers.dumpMysql(SOURCE_EV, raw.resolve(DumpMasking.EV_FILE))
        mongo.drop()
    }

    private fun mask(
        out: Path,
        dropUnknown: Boolean = true,
    ) = DumpMasking.run(
        MaskOptions(raw.resolve(DumpMasking.MONGO_FILE), raw.resolve(DumpMasking.EV_FILE), out, secret, SOURCE_DB, dropUnknown),
    )

    private fun rehearse(
        dir: Path,
        out: Path,
        allowUnmasked: Boolean = false,
    ) = Rehearsal.run(
        RehearsalOptions(
            dir.resolve(DumpMasking.MONGO_FILE),
            dir.resolve(DumpMasking.EV_FILE),
            out,
            sampleUsers = 40,
            parityUsers = 10,
            allowUnmasked = allowUnmasked,
        ),
    )

    @Test
    fun `마스킹한 덤프로 리허설이 끝까지 돌고 모든 검증을 통과한다`() {
        val masked = work.resolve("masked")
        val maskReport = mask(masked)
        assertEquals(listOf("feedbacks"), maskReport.droppedCollections)
        assertEquals(1L, maskReport.removedFields["users.phoneNumber"])

        val rawText = dumpText(raw)
        val maskedText = dumpText(masked)
        assertEquals(emptyList<String>(), data.sensitive.filterNot { contains(rawText, it) }, "원본 덤프에 원문이 있어야 아래 검사가 의미 있다")
        assertEquals(emptyList<String>(), data.sensitive.filter { contains(maskedText, it) }, "마스킹 뒤에도 원문이 남았다")

        val report = rehearse(masked, work.resolve("report"))
        assertTrue(report.passed) { "리허설 실패:\n" + report.failures.joinToString("\n") }
        assertTrue(Files.isRegularFile(work.resolve("report/rehearsal-report.md")))
        assertTrue(Files.isRegularFile(work.resolve("report/rehearsal-report.json")))
        val roles = Files.readString(work.resolve("report/roles-sample.yml"))
        assertEquals(10, Regex("sample-\\d{3}: \"[0-9a-f]{24}\"").findAll(roles).count())

        // 합성 데이터가 이관의 레거시 정리 규칙을 실제로 거치는지(검증이 빈 데이터로 통과하지 않게)
        val exercised = report.resolutions.keys
        listOf(
            "같은 아이디를 쓰는 활성 계정이 여럿이라 로컬 로그인 수단을 제거",
            "같은 이메일이 인증된 활성 계정이 여럿이라 인증 상태를 해제",
            "같은 소셜 계정을 쓰는 활성 계정이 여럿이라 소셜 로그인 수단을 제거",
            "같은 구 토큰을 가진 활성 계정이 여럿이라 토큰을 이관하지 않음",
            "같은 (연도, 학기, 교과목번호, 분반)의 강의가 중복되어 하나로 합침",
            "구 ev course 중복을 하나로 합쳐 이관",
            "강의와 강의평이 모두 없는 course를 제외",
            "사용자가 없는 시간표를 제외",
            "같은 학기에 제목이 중복되어 번호를 붙임",
            "같은 학기의 대표 시간표가 여럿이라 가장 최근에 수정한 시간표만 대표로 남김",
            "범위 밖의 구 팔레트 번호를 정규화",
            "올바르지 않은 사용자 지정 색상 대신 팔레트 색상을 사용",
            "동일한 온라인 테마의 중복 다운로드를 합침",
            "기존 다운로드 내용을 비공개 스냅샷으로 보존",
            "하나로 합쳐진 강의를 가리키는 북마크 항목을 제외",
            "같은 사용자·강의의 빈자리 알림이 중복되어 제외",
            "같은 FCM 등록 토큰의 활성 기기가 중복되어 이전 항목을 비활성화",
            "탈퇴한 사용자의 친구 관계를 제외",
            "같은 사용자 쌍의 친구 관계가 중복되어 하나만 남김",
            "사용자가 없는 강의평 공감을 제외",
            "사용자가 없는 강의평 신고를 제외",
            "대상을 찾을 수 없는 알림 deeplink를 제거",
        ).forEach { assertTrue(it in exercised) { "합성 데이터가 '$it' 규칙을 거치지 않는다: $exercised" } }
        assertTrue(report.counts.first { it.label.startsWith("user.is_email_verified NULL") }.actual > 0)
    }

    @Test
    fun `마스킹은 이관이 보는 관계와 분포를 바꾸지 않는다`() {
        val masked = work.resolve("masked-relations")
        mask(masked)
        val maskedReport = rehearse(masked, work.resolve("report-masked-relations"))
        // 마스킹 표식이 없는 덤프는 기본으로 거부하고, 리포트에 이유를 남긴다.
        val refused = rehearse(raw, work.resolve("report-raw-refused"))
        assertTrue(!refused.passed && refused.failures.single().contains("마스킹 표식")) { refused.failures.joinToString("\n") }
        assertTrue(Files.isRegularFile(work.resolve("report-raw-refused/rehearsal-report.md")))

        val rawReport = rehearse(raw, work.resolve("report-raw"), allowUnmasked = true)
        assertTrue(rawReport.passed) { rawReport.failures.joinToString("\n") }
        assertEquals(rawReport.counts.map { it.label to it.actual }, maskedReport.counts.map { it.label to it.actual })
        assertEquals(rawReport.resolutions, maskedReport.resolutions)
    }

    @Test
    fun `같은 비밀값이면 같은 결과가 나오고 길이와 null 비율이 그대로다`() {
        val a = work.resolve("masked-a")
        val b = work.resolve("masked-b")
        mask(a)
        mask(b)
        val client = RehearsalContainers.mongoClient
        RehearsalContainers.restoreMongo(raw.resolve(DumpMasking.MONGO_FILE), SOURCE_DB, "check_raw")
        RehearsalContainers.restoreMongo(a.resolve(DumpMasking.MONGO_FILE), SOURCE_DB, "check_a")
        RehearsalContainers.restoreMongo(b.resolve(DumpMasking.MONGO_FILE), SOURCE_DB, "check_b")
        val (rawDb, dbA, dbB) = listOf("check_raw", "check_a", "check_b").map(client::getDatabase)

        MaskingPlan.MONGO_PII_COLLECTIONS.forEach { collection ->
            assertEquals(documents(dbA, collection), documents(dbB, collection), "$collection: 같은 비밀값인데 결과가 다르다")
        }
        DISTRIBUTION_PATHS.forEach { (collection, path) ->
            assertEquals(shape(rawDb, collection, path), shape(dbA, collection, path), "$collection.$path 의 길이·null 분포가 바뀌었다")
        }
        val rawUsers = documents(rawDb, "users")
        val maskedUsers = documents(dbA, "users")
        assertEquals(rawUsers.keys, maskedUsers.keys, "사용자 id(ObjectId)는 그대로다")
        assertTrue(rawUsers.keys.any { rawUsers[it] != maskedUsers[it] })

        RehearsalContainers.restoreMysql(raw.resolve(DumpMasking.EV_FILE), "check_ev_raw")
        RehearsalContainers.restoreMysql(a.resolve(DumpMasking.EV_FILE), "check_ev_a")
        val sql = "SELECT id, user_id, CHAR_LENGTH(content), rating FROM lecture_evaluation ORDER BY id"
        assertEquals(RehearsalContainers.jdbc("check_ev_raw").queryForList(sql), RehearsalContainers.jdbc("check_ev_a").queryForList(sql))
        assertEquals(
            0L,
            RehearsalContainers.jdbc("check_ev_a").queryForObject(
                "SELECT COUNT(*) FROM lecture_evaluation a JOIN check_ev_raw.lecture_evaluation r ON r.id = a.id WHERE a.content = r.content",
                Long::class.java,
            ),
        )
        listOf(rawDb, dbA, dbB).forEach(MongoDatabase::drop)
    }

    @Test
    fun `검토하지 않은 컬렉션이 있으면 마스킹을 멈춘다`() {
        val error = assertThrows<IllegalStateException> { mask(work.resolve("masked-refused"), dropUnknown = false) }
        assertTrue(error.message!!.contains("feedbacks")) { error.message }
        assertTrue(Files.notExists(work.resolve("masked-refused").resolve(DumpMasking.MONGO_FILE)))
    }

    private fun documents(
        db: MongoDatabase,
        collection: String,
    ): Map<Any?, String> = db.getCollection(collection).find().associate { it["_id"] to it.toJson() }

    /** 경로 값들의 (null 여부, 코드포인트 길이) 목록. 정렬해서 비교한다. */
    private fun shape(
        db: MongoDatabase,
        collection: String,
        path: String,
    ): List<String> =
        db
            .getCollection(collection)
            .find()
            .flatMap { doc -> values(doc, path.split('.')) }
            .map { value -> if (value == null) "null" else (value as String).codePointCount(0, value.length).toString() }
            .sorted()

    private fun values(
        node: Any?,
        path: List<String>,
    ): List<Any?> {
        if (path.isEmpty()) return listOf(node)
        return when (node) {
            is Document -> values(node[path.first()], path.drop(1))
            is List<*> -> node.flatMap { values(it, path) }
            else -> listOf(null)
        }
    }

    private fun dumpText(dir: Path) = gunzipText(dir.resolve(DumpMasking.MONGO_FILE)) + "\n" + gunzipText(dir.resolve(DumpMasking.EV_FILE))

    /** mysqldump는 문자열 안의 줄바꿈을 `\n`으로 쓴다. */
    private fun contains(
        text: String,
        value: String,
    ) = value in text || value.replace("\n", "\\n") in text

    private fun gunzipText(file: Path): String {
        val out = ByteArrayOutputStream()
        GZIPInputStream(Files.newInputStream(file)).use { it.copyTo(out) }
        return out.toString(Charsets.UTF_8)
    }

    companion object {
        private const val SOURCE_DB = "snutt"
        private const val SOURCE_EV = "synthetic_ev"

        private val DISTRIBUTION_PATHS =
            listOf(
                "users" to "email",
                "users" to "nickname",
                "users" to "credential.localId",
                "users" to "credential.localPw",
                "users" to "credential.fbId",
                "users" to "credential.appleEmail",
                "users" to "credentialHash",
                "friend" to "fromUserDisplayName",
                "friend" to "toUserDisplayName",
                "userDevice" to "fcmRegistrationId",
                "userDevice" to "deviceId",
                "timetables" to "title",
                "timetables" to "lecture_list.course_title",
                "timetables" to "lecture_list.instructor",
                "timetableTheme" to "name",
                "notifications" to "message",
                "diarySubmission" to "comment",
            )
    }
}
