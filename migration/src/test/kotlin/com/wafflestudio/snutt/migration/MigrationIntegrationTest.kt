package com.wafflestudio.snutt.migration

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.wafflestudio.snutt.migration.step.AggregateStep
import com.wafflestudio.snutt.migration.step.CatalogStep
import com.wafflestudio.snutt.migration.step.CourseStep
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
import com.wafflestudio.snutt.v1compat.config.V1CompatSchemaInitializer
import org.bson.Document
import org.bson.types.ObjectId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer
import java.security.MessageDigest
import java.util.Date
import java.util.HexFormat

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationIntegrationTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var ev: EvSource
    private lateinit var mongoClient: MongoClient
    private lateinit var context: MigrationContext

    private val userWithBoth = ObjectId()
    private val userDupOld = ObjectId()
    private val userDupNew = ObjectId()
    private val userShareA = ObjectId()
    private val userShareB = ObjectId()
    private val lectureId = ObjectId()
    private val lectureDuplicate = ObjectId()
    private val timetableId = ObjectId()
    private val themeOrigin = ObjectId()
    private val themeCopy = ObjectId()

    @BeforeAll
    fun setUp() {
        targetMysql.start()
        evMysql.start()
        mongo.start()

        jdbc = JdbcTemplate(dataSource(targetMysql))
        Flyway
            .configure()
            .dataSource(targetMysql.jdbcUrl, targetMysql.username, targetMysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        Flyway
            .configure()
            .dataSource(targetMysql.jdbcUrl, targetMysql.username, targetMysql.password)
            .locations(V1CompatSchemaInitializer.LOCATION)
            .table(V1CompatSchemaInitializer.HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()

        ev = EvSource(evMysql.jdbcUrl, evMysql.username, evMysql.password)
        createEvSchema()
        seedEv()

        mongoClient = MongoClients.create("mongodb://${mongo.host}:${mongo.getMappedPort(27017)}")
        seedMongo()

        context = MigrationContext()
        val mongoSource = MongoSource(mongoClient, "snutt")
        listOf(
            CatalogStep(jdbc, context, mongoSource),
            UserStep(jdbc, context, mongoSource),
            CourseStep(jdbc, context, mongoSource, ev),
            LectureStep(jdbc, context, mongoSource),
            ThemeStep(jdbc, context, mongoSource),
            TimetableStep(jdbc, context, mongoSource),
            UserDataStep(jdbc, context, mongoSource),
            NotificationStep(jdbc, context, mongoSource),
            EvaluationStep(jdbc, context, ev),
            AggregateStep(jdbc, context),
            LegacyTokenStep(jdbc, context, mongoSource),
            LegacySearchTagStep(jdbc, context, ev),
            ValidateStep(jdbc, context, mongoSource, ev),
        ).forEach { it.run() }
    }

    @Test
    fun `credential 중첩 문서가 평면 컬럼으로 펴진다`() {
        val row =
            jdbc.queryForMap(
                "SELECT local_id, local_pw, is_admin, last_login_at FROM `user` WHERE id = ?",
                context.userIds[userWithBoth.toHexString()],
            )
        assertEquals("waffle", row["local_id"])
        assertEquals("encoded-pw", row["local_pw"])
        assertEquals(true, row["is_admin"])
        assertNotNull(row["last_login_at"])

        val social =
            jdbc.queryForMap(
                "SELECT provider, sub, display_name FROM user_social_auth WHERE user_id = ?",
                context.userIds[userWithBoth.toHexString()],
            )
        assertEquals("facebook", social["provider"])
        assertEquals("fb-1", social["sub"])
        assertEquals("김와플", social["display_name"])
    }

    @Test
    fun `같은 아이디를 쓰는 활성 계정은 최종 로그인이 최신인 쪽만 남는다`() {
        val owners =
            jdbc.queryForList(
                "SELECT id FROM `user` WHERE local_id = 'dup' AND active = TRUE",
                Long::class.java,
            )
        assertEquals(listOf(context.userIds[userDupNew.toHexString()]), owners)
        assertNull(
            jdbc.queryForObject(
                "SELECT local_pw FROM `user` WHERE id = ?",
                String::class.java,
                context.userIds[userDupOld.toHexString()],
            ),
        )
    }

    @Test
    fun `course는 구 ev lecture id를 승계하고 강의가 그것을 참조한다`() {
        assertEquals(
            "Chenglin Fan",
            jdbc.queryForObject("SELECT instructor FROM course WHERE id = ?", String::class.java, EV_LECTURE_ID),
        )
        assertEquals(
            EV_LECTURE_ID,
            jdbc.queryForObject(
                "SELECT course_id FROM lecture WHERE id = ?",
                Long::class.java,
                context.lectureIds[lectureId.toHexString()],
            ),
        )
    }

    @Test
    fun `중복 개설은 하나로 합쳐지고 참조가 살아남은 행을 가리킨다`() {
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM lecture", Long::class.java))
        assertEquals(context.lectureIds[lectureId.toHexString()], context.lectureIds[lectureDuplicate.toHexString()])
    }

    @Test
    fun `시간표 강의는 달라진 값만 남기고 같은 값은 강의를 따른다`() {
        val referenced =
            jdbc.queryForMap(
                "SELECT overrides->>'$.courseTitle' AS courseTitle, overrides->>'$.instructor' AS instructor, " +
                    "overrides->>'$.classPlaceAndTimes' AS classPlaceAndTimes, lecture_id FROM timetable_lecture WHERE lecture_id IS NOT NULL",
            )
        assertEquals("내가 바꾼 이름", referenced["courseTitle"])
        assertNull(referenced["instructor"])
        assertNull(referenced["classPlaceAndTimes"])

        val custom =
            jdbc.queryForMap(
                "SELECT overrides->>'$.courseTitle' AS courseTitle, overrides->>'$.classPlaceAndTimes' AS classPlaceAndTimes " +
                    "FROM timetable_lecture WHERE lecture_id IS NULL",
            )
        assertEquals("직접 만든 강의", custom["courseTitle"])
        assertNotNull(custom["classPlaceAndTimes"])
    }

    @Test
    fun `테마는 색상과 공개 정보와 원본 참조를 유지한다`() {
        val copy =
            jdbc.queryForMap(
                "SELECT colors, origin_theme_id FROM theme WHERE id = ?",
                context.themeIds[themeCopy.toHexString()],
            )
        assertTrue((copy["colors"] as String).contains("backgroundColor"))
        assertEquals(context.themeIds[themeOrigin.toHexString()], copy["origin_theme_id"])
        assertEquals(
            "공개된 테마",
            jdbc.queryForObject("SELECT publish_name FROM published_theme", String::class.java),
        )
    }

    @Test
    fun `알림 종류는 이름으로 저장된다`() {
        assertEquals(
            listOf("DIARY", "LECTURE_UPDATE"),
            jdbc.queryForList("SELECT type FROM notification ORDER BY type", String::class.java),
        )
    }

    @Test
    fun `강의평은 id를 유지하고 개설이 아니라 과목과 학기에 붙는다`() {
        val row =
            jdbc.queryForMap(
                "SELECT course_id, year, semester, user_id, like_count FROM evaluation WHERE id = ?",
                EV_EVALUATION_ID,
            )
        assertEquals(EV_LECTURE_ID, row["course_id"])
        assertEquals(2026, row["year"])
        assertEquals(3, (row["semester"] as Number).toInt())
        assertEquals(context.userIds[userWithBoth.toHexString()], row["user_id"])
        assertEquals(1L, (row["like_count"] as Number).toLong())
    }

    @Test
    fun `과목 집계가 강의평과 일치한다`() {
        val row = jdbc.queryForMap("SELECT eval_count, avg_rating FROM course WHERE id = ?", EV_LECTURE_ID)
        assertEquals(1L, (row["eval_count"] as Number).toLong())
        assertEquals(4.0, (row["avg_rating"] as Number).toDouble())
    }

    @Test
    fun `구 토큰은 사용자를 특정할 수 있을 때만 옮긴다`() {
        val userId = context.userIds[userWithBoth.toHexString()]
        assertEquals(
            userId,
            jdbc.queryForObject(
                "SELECT user_id FROM legacy_access_token WHERE token_hash = ?",
                Long::class.java,
                sha256Hex("hash-both"),
            ),
        )
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM legacy_access_token WHERE token_hash = ?",
                Long::class.java,
                sha256Hex("hash-shared"),
            ),
        )
    }

    @Test
    fun `검색 태그는 큐레이션 그룹을 뺀 나머지만 옮긴다`() {
        val groups =
            jdbc.queryForList("SELECT DISTINCT group_name FROM legacy_search_tag", String::class.java)
        assertEquals(listOf("학과"), groups)
        assertEquals(
            "컴퓨터공학부",
            jdbc.queryForObject("SELECT string_value FROM legacy_search_tag WHERE id = 30", String::class.java),
        )
    }

    private fun seedMongo() {
        val db = mongoClient.getDatabase("snutt")
        db.getCollection("coursebooks").insertOne(
            Document("_id", ObjectId()).append("year", 2026).append("semester", 3).append("updated_at", Date()),
        )
        db.getCollection("users").insertMany(
            listOf(
                userDocument(userWithBoth, "waffle", "hash-both", localId = "waffle", isAdmin = true),
                userDocument(userDupOld, "dup-old", "hash-dup-old", localId = "dup", lastLogin = 1_000L),
                userDocument(userDupNew, "dup-new", "hash-dup-new", localId = "dup", lastLogin = 2_000L),
                userDocument(userShareA, "share-a", "hash-shared", localId = "share-a"),
                userDocument(userShareB, "share-b", "hash-shared", localId = "share-b"),
            ),
        )
        db.getCollection("lectures").insertMany(
            listOf(lectureDocument(lectureId), lectureDocument(lectureDuplicate)),
        )
        db.getCollection("timetableTheme").insertMany(
            listOf(
                Document("_id", themeOrigin)
                    .append("userId", userWithBoth)
                    .append("name", "원본 테마")
                    .append("isCustom", true)
                    .append("colors", listOf(Document("bg", "#111111").append("fg", "#FFFFFF")))
                    .append("publishInfo", Document("publishName", "공개된 테마").append("authorAnonymous", true).append("downloads", 7)),
                Document("_id", themeCopy)
                    .append("userId", userWithBoth)
                    .append("name", "받아온 테마")
                    .append("isCustom", true)
                    .append("colors", listOf(Document("bg", "#222222").append("fg", "#000000")))
                    .append("origin", Document("originId", themeOrigin.toHexString()).append("authorId", userWithBoth.toHexString())),
            ),
        )
        db.getCollection("timetables").insertOne(
            Document("_id", timetableId)
                .append("user_id", userWithBoth)
                .append("year", 2026)
                .append("semester", 3)
                .append("title", "나의 시간표")
                .append("theme", 0)
                .append("is_primary", true)
                .append("updated_at", Date())
                .append(
                    "lecture_list",
                    listOf(
                        Document("_id", ObjectId())
                            .append("lecture_id", lectureId)
                            .append("course_title", "내가 바꾼 이름")
                            .append("instructor", "Chenglin Fan")
                            .append("class_time_json", listOf(classTime(0), classTime(2)))
                            .append("colorIndex", 3),
                        Document("_id", ObjectId())
                            .append("course_title", "직접 만든 강의")
                            .append("class_time_json", listOf(classTime(4)))
                            .append("colorIndex", 1),
                    ),
                ),
        )
        db.getCollection("notifications").insertMany(
            listOf(
                Document("_id", ObjectId())
                    .append("user_id", userWithBoth)
                    .append("title", "알림")
                    .append("message", "본문")
                    .append("type", 2)
                    .append("created_at", Date()),
                Document("_id", ObjectId())
                    .append("user_id", userWithBoth)
                    .append("title", "일기장")
                    .append("message", "본문")
                    .append("type", 7)
                    .append("created_at", Date()),
            ),
        )
    }

    private fun userDocument(
        id: ObjectId,
        nickname: String,
        credentialHash: String,
        localId: String? = null,
        isAdmin: Boolean = false,
        lastLogin: Long = 1_700_000_000_000L,
    ) = Document("_id", id)
        .append("email", "$nickname@snu.ac.kr")
        .append("nickname", nickname)
        .append("isEmailVerified", false)
        .append(
            "credential",
            Document("localId", localId)
                .append("localPw", localId?.let { "encoded-pw" })
                .append("fbId", if (nickname == "waffle") "fb-1" else null)
                .append("fbName", if (nickname == "waffle") "김와플" else null),
        ).append("credentialHash", credentialHash)
        .append("active", true)
        .append("isAdmin", isAdmin)
        .append("regDate", Date())
        .append("lastLoginTimestamp", lastLogin)
        .append("notificationCheckedAt", Date())

    private fun lectureDocument(id: ObjectId) =
        Document("_id", id)
            .append("year", 2026)
            .append("semester", 3)
            .append("course_number", "M1522.004700")
            .append("lecture_number", "001")
            .append("course_title", "계산이론연구 (Theoretical Foundation of AI)")
            .append("instructor", "Chenglin Fan")
            .append("department", "컴퓨터공학부")
            .append("academic_year", "석박사통합")
            .append("classification", "전선")
            .append("credit", 3)
            .append("quota", 10)
            .append("registrationCount", 7)
            .append("remark", "Ⓔ®강의 교수의 지도학생만 수강신청 가능")
            .append("course_title_en", "Studies in Theory of Computation (Theoretical Foundation of AI)")
            .append("department_en", "Department of Computer Science and Engineering")
            .append("academic_year_en", "Combined Masters/Doctorate")
            .append("classification_en", "Elective Subject for Major")
            .append("class_time_json", listOf(classTime(0), classTime(2)))

    private fun classTime(day: Int) = Document("day", day).append("place", "302-107").append("startMinute", 930).append("endMinute", 1005)

    private fun createEvSchema() {
        listOf(
            "CREATE TABLE lecture (id BIGINT PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, " +
                "academic_year VARCHAR(255), category VARCHAR(255), classification VARCHAR(255), course_number VARCHAR(255), " +
                "credit INT NOT NULL, department VARCHAR(255), instructor VARCHAR(255), title VARCHAR(255))",
            "CREATE TABLE semester_lecture (id BIGINT PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, " +
                "academic_year VARCHAR(255), category VARCHAR(255), classification VARCHAR(255), credit INT NOT NULL, " +
                "extra_info LONGTEXT, semester INT NOT NULL, year INT NOT NULL, lecture_id BIGINT NOT NULL)",
            "CREATE TABLE lecture_evaluation (id BIGINT PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, " +
                "content LONGTEXT NOT NULL, gains DOUBLE, grade_satisfaction DOUBLE, is_hidden BIT(1) NOT NULL, " +
                "is_reported BIT(1) NOT NULL, life_balance DOUBLE, like_count BIGINT NOT NULL, rating DOUBLE NOT NULL, " +
                "teaching_skill DOUBLE, user_id VARCHAR(255) NOT NULL, semester_lecture_id BIGINT NOT NULL, from_snuev BIT(1) NOT NULL)",
            "CREATE TABLE evaluation_like (id BIGINT PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, " +
                "user_id VARCHAR(255) NOT NULL, lecture_evaluation_id BIGINT NOT NULL)",
            "CREATE TABLE evaluation_report (id BIGINT PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, " +
                "content LONGTEXT NOT NULL, is_hidden BIT(1) NOT NULL, user_id VARCHAR(255) NOT NULL, lecture_evaluation_id BIGINT NOT NULL)",
            "CREATE TABLE tag_group (id BIGINT PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, " +
                "color VARCHAR(255), name VARCHAR(255) NOT NULL, ordering INT NOT NULL, value_type VARCHAR(255) NOT NULL)",
            "CREATE TABLE tag (id BIGINT PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, " +
                "description VARCHAR(255), int_value INT, name VARCHAR(255) NOT NULL, ordering INT NOT NULL, " +
                "string_value VARCHAR(255), tag_group_id BIGINT NOT NULL)",
        ).forEach { ev.jdbc.execute(it) }
    }

    private fun seedEv() {
        ev.jdbc.update(
            "INSERT INTO lecture VALUES (?, NOW(6), NOW(6), '석박사통합', '', '전선', 'M1522.004700', 3, " +
                "'컴퓨터공학부', 'Chenglin Fan', '계산이론연구 (Theoretical Foundation of AI)')",
            EV_LECTURE_ID,
        )
        ev.jdbc.update(
            "INSERT INTO semester_lecture VALUES (?, NOW(6), NOW(6), '석박사통합', '', '전선', 3, NULL, 3, 2026, ?)",
            EV_SEMESTER_LECTURE_ID,
            EV_LECTURE_ID,
        )
        ev.jdbc.update(
            "INSERT INTO lecture_evaluation VALUES (?, NOW(6), NOW(6), '좋은 강의', 4, 4, b'0', b'0', 4, 5, 4.0, 4, ?, ?, b'0')",
            EV_EVALUATION_ID,
            userWithBoth.toHexString(),
            EV_SEMESTER_LECTURE_ID,
        )
        ev.jdbc.update(
            "INSERT INTO evaluation_like VALUES (1, NOW(6), NOW(6), ?, ?)",
            userDupNew.toHexString(),
            EV_EVALUATION_ID,
        )
        ev.jdbc.update(
            "INSERT INTO evaluation_like VALUES (2, NOW(6), NOW(6), ?, ?)",
            "없는-사용자",
            EV_EVALUATION_ID,
        )
        ev.jdbc.update("INSERT INTO tag_group VALUES (1, NOW(6), NOW(6), NULL, 'main', -1, 'LOGIC')")
        ev.jdbc.update("INSERT INTO tag_group VALUES (5, NOW(6), NOW(6), '#1BD0C8', '학과', 4, 'STRING')")
        ev.jdbc.update("INSERT INTO tag VALUES (1, NOW(6), NOW(6), '최근 등록된 강의평', NULL, '최신', 1, NULL, 1)")
        ev.jdbc.update("INSERT INTO tag VALUES (30, NOW(6), NOW(6), NULL, NULL, '컴퓨터공학부', 1, '컴퓨터공학부', 5)")
    }

    private fun dataSource(container: MySQLContainer) =
        DataSourceBuilder
            .create()
            .url(container.jdbcUrl)
            .username(container.username)
            .password(container.password)
            .build()

    private fun sha256Hex(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    companion object {
        private const val EV_LECTURE_ID = 65_000L
        private const val EV_SEMESTER_LECTURE_ID = 216_000L
        private const val EV_EVALUATION_ID = 33_000L

        @JvmStatic
        val targetMysql: MySQLContainer =
            MySQLContainer("mysql:8.4")
                .withDatabaseName("snutt")
                .withUrlParam("rewriteBatchedStatements", "true")

        @JvmStatic
        val evMysql: MySQLContainer = MySQLContainer("mysql:8.4").withDatabaseName("snutt_ev")

        @JvmStatic
        val mongo: GenericContainer<*> = GenericContainer("mongo:7").withExposedPorts(27017)
    }
}
