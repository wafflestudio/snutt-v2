package com.wafflestudio.snutt.migration.step

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.wafflestudio.snutt.migration.IdMaps
import com.wafflestudio.snutt.migration.MigrationStep
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

abstract class AbstractMigrationStep(
    protected val mongoClient: MongoClient,
    protected val jdbc: JdbcTemplate,
    protected val idMaps: IdMaps,
) : MigrationStep {
    protected val log = LoggerFactory.getLogger(javaClass)

    protected fun collection(name: String): MongoCollection<Document> = mongoClient.getDatabase("snutt").getCollection(name)

    protected fun now(): Instant = Instant.now()

    protected fun insert(
        table: String,
        columns: List<String>,
        values: List<Any?>,
    ) {
        val placeholders = columns.joinToString(",") { "?" }
        jdbc.update(
            "INSERT INTO $table (${columns.joinToString(",")}) VALUES ($placeholders)",
            *values.toTypedArray(),
        )
    }

    protected fun lastInsertId(): Long = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java) ?: error("LAST_INSERT_ID 실패")

    // ObjectId 필드 (없으면 랜덤 24-hex 생성 — 이관 대상에 없는 신규 필드 보정)
    protected fun Document.externalId(key: String = "_id"): String =
        (get(key) as? org.bson.types.ObjectId)?.toHexString()
            ?: com.wafflestudio.snutt.core.common.model.ExternalIdGenerator
                .generate()

    protected fun Document.string(key: String): String? = get(key)?.toString()

    protected fun Document.instant(key: String): Instant? = (get(key) as? java.util.Date)?.toInstant() ?: now()

    protected fun Document.int(key: String): Int? = (get(key) as? Number)?.toInt()

    protected fun Document.long(key: String): Long? = (get(key) as? Number)?.toLong()

    protected fun Document.bool(key: String): Boolean = get(key) as? Boolean ?: false

    protected fun Document.semester(): Int = (get("semester") as? Number)?.toInt() ?: 1

    protected fun Document.year(): Int = (get("year") as? Number)?.toInt() ?: 2026

    protected fun Document.isoInstant(key: String): Instant? =
        when (val value = get(key)) {
            is java.util.Date -> value.toInstant()
            is String -> runCatching { Instant.parse(value) }.getOrNull()
            else -> null
        }
}

// 신 MySQL은 이미 Flyway V1로 스키마가 만들어져 있다고 가정하고, 데이터만 이관한다
@Component
class UsersStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "users"

    override fun run() {
        val users = collection("users")
        var count = 0
        users.find().forEach { doc ->
            val oldId = (doc["_id"] as org.bson.types.ObjectId).toHexString()
            insert(
                "`user`",
                listOf(
                    "external_id",
                    "email",
                    "is_email_verified",
                    "nickname",
                    "local_id",
                    "local_pw",
                    "facebook_sub",
                    "facebook_name",
                    "apple_sub",
                    "apple_transfer_sub",
                    "apple_email",
                    "google_sub",
                    "google_email",
                    "kakao_sub",
                    "kakao_email",
                    "credential_hash",
                    "fcm_key",
                    "active",
                    "is_admin",
                    "last_login_at",
                    "notification_checked_at",
                    "created_at",
                    "updated_at",
                ),
                listOf(
                    oldId,
                    doc.string("email"),
                    doc.bool("is_email_verified"),
                    doc.string("nickname") ?: "snuttian",
                    doc.string("local_id"),
                    doc.string("local_pw"),
                    doc.string("facebook_sub"),
                    doc.string("facebook_name"),
                    doc.string("apple_sub"),
                    doc.string("apple_transfer_sub"),
                    doc.string("apple_email"),
                    doc.string("google_sub"),
                    doc.string("google_email"),
                    doc.string("kakao_sub"),
                    doc.string("kakao_email"),
                    doc.string("credential_hash") ?: "",
                    doc.string("fcm_key"),
                    doc.bool("active"),
                    doc.bool("is_admin"),
                    java.sql.Timestamp.from(doc.instant("last_login_at") ?: now()),
                    java.sql.Timestamp.from(doc.instant("notification_checked_at") ?: now()),
                    java.sql.Timestamp.from(doc.instant("created_at") ?: now()),
                    java.sql.Timestamp.from(doc.instant("updated_at") ?: now()),
                ),
            )
            idMaps.put("user", oldId, lastInsertId())
            count++
        }
        log.info("users 이관: {}건", count)
    }
}

// lecture: 분반 단위. course_id는 구 snutt_lecture_id_map → semester_lecture → lecture 경로로 해석 (PLAN.md §5)
@Component
class LectureStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
    private val oldEvJdbc: org.springframework.jdbc.core.JdbcTemplate,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "lecture"

    // course_number+instructor → 신 course id (users step 이후 실행되는 course step이 채운다)
    private val courseIdByKey = mutableMapOf<String, Long>()

    override fun run() {
        jdbc.query("SELECT id, course_number, instructor FROM course") { rs ->
            courseIdByKey["${rs.getString("course_number")}##${rs.getString("instructor")}"] = rs.getLong("id")
        }

        // 구 ev: semester_lecture id → lecture(course) id
        val lectureIdBySemesterLectureId = mutableMapOf<Long, Long>()
        oldEvJdbc.query("SELECT id, lecture_id FROM semester_lecture") { rs ->
            lectureIdBySemesterLectureId[rs.getLong("id")] = rs.getLong("lecture_id")
        }
        val courseIdByEvLectureId = mutableMapOf<Long, Long>()
        oldEvJdbc.query("SELECT id, course_number, instructor FROM lecture") { rs ->
            courseIdByEvLectureId[rs.getLong("id")] =
                courseIdByKey["${rs.getString("course_number")}##${rs.getString("instructor")}"] ?: 0L
        }

        val lectures = collection("lectures")
        var count = 0
        lectures.find().forEach { doc ->
            val oldId = doc.externalId()
            // SnuttLectureIdMap: snutt_lecture_id(snutt 강의 _id) → semester_lecture id
            val semesterLectureId =
                oldEvJdbc.queryForObject(
                    "SELECT semester_lecture_id FROM snutt_lecture_id_map WHERE snutt_id = ?",
                    Long::class.java,
                    oldId,
                )
            val evLectureId = semesterLectureId?.let { lectureIdBySemesterLectureId[it] }
            val courseId = evLectureId?.let { courseIdByEvLectureId[it] }?.takeIf { it != 0L }

            val classTimes = doc.get("class_time_json") as? List<*> ?: emptyList<Any>()

            insert(
                "lecture",
                listOf(
                    "external_id",
                    "course_id",
                    "year",
                    "semester",
                    "course_number",
                    "lecture_number",
                    "course_title",
                    "instructor",
                    "department",
                    "academic_year",
                    "category",
                    "category_pre2025",
                    "classification",
                    "credit",
                    "quota",
                    "freshman_quota",
                    "remark",
                    "registration_count",
                    "was_full",
                    "created_at",
                    "updated_at",
                ),
                listOf(
                    oldId,
                    courseId,
                    doc.year(),
                    doc.semester(),
                    doc.string("course_number") ?: "",
                    doc.string("lecture_number") ?: "",
                    doc.string("course_title") ?: "",
                    doc.string("instructor"),
                    doc.string("department"),
                    doc.string("academic_year"),
                    doc.string("category"),
                    doc.string("category_pre2025"),
                    doc.string("classification"),
                    doc.int("credit") ?: 0,
                    doc.int("quota") ?: 0,
                    doc.int("freshman_quota"),
                    doc.string("remark"),
                    doc.int("registration_count") ?: 0,
                    doc.bool("was_full"),
                    java.sql.Timestamp.from(doc.instant("created_at") ?: now()),
                    java.sql.Timestamp.from(doc.instant("updated_at") ?: now()),
                ),
            )
            val newLectureId = lastInsertId()
            idMaps.put("lecture", oldId, newLectureId)

            // 검색용 lecture_class_time 정규화 사본
            classTimes.forEach { time ->
                val t = time as Document
                val day = (t["day"] as? Number)?.toInt() ?: 0
                val startMinute = (t["start_minute"] as? Number)?.toInt() ?: 0
                val endMinute = (t["end_minute"] as? Number)?.toInt() ?: 0
                jdbc.update(
                    "INSERT INTO lecture_class_time (lecture_id, day, place, start_minute, end_minute) VALUES (?, ?, ?, ?, ?)",
                    newLectureId,
                    day,
                    t.string("place"),
                    startMinute,
                    endMinute,
                )
            }
            count++
        }
        log.info("lecture 이관: {}건", count)
    }
}

@Component
class ValidateStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "validate"

    override fun run() {
        // 행 수 대조
        val expectedUsers = collection("users").countDocuments()
        val actualUsers = jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Long::class.java)
        check(expectedUsers == actualUsers) { "users 행 수 불일치: $expectedUsers vs $actualUsers" }
        log.info("users 행 수 일치: {}", actualUsers)

        // 외부 id 체크섬 (CRC32 합)
        val checksum =
            jdbc.queryForObject(
                "SELECT SUM(CRC32(external_id)) FROM `user`",
                Long::class.java,
            ) ?: 0L
        log.info("users external_id 체크섬: {}", checksum)

        // 외부 id 중복 검사
        val duplicates =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT external_id FROM `user` GROUP BY external_id HAVING COUNT(*) > 1) t",
                Long::class.java,
            )
        check(duplicates == 0L) { "중복 external_id $duplicates 건" }
        log.info("검증 완료")
    }
}
