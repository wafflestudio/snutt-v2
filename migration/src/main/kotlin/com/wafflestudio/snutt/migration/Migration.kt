package com.wafflestudio.snutt.migration

import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

interface MigrationStep {
    val name: String

    val tables: List<String>

    fun run()
}

@Component
class MigrationContext {
    val userIds = HashMap<String, Long>(256_000)
    val lectureIds = HashMap<String, Long>(256_000)
    val timetableIds = HashMap<String, Long>(512_000)
    val themeIds = HashMap<String, Long>()
    val themePalettes = HashMap<Long, List<ColorSet>>()
    val diaryClassTypeIds = HashMap<String, Long>()
    val diaryQuestionIds = HashMap<String, Long>()
    val timetableLectureIds = HashMap<Pair<String, String>, Long>(1_024_000)
    val timetableLectureIdsByLecture = HashMap<Pair<Long, Long>, Long>(1_024_000)

    val courseIds = HashMap<String, Long>(64_000)
    val courseIdRemap = HashMap<Long, Long>()

    val lectureSnapshots = HashMap<Long, LectureSnapshot>(256_000)
    val lectureSemesters = HashSet<Pair<Int, Int>>()

    val resolutions = LinkedHashMap<String, Long>()

    private val stringPool = HashMap<String, String>(64_000)

    fun resolved(reason: String) {
        resolutions[reason] = (resolutions[reason] ?: 0L) + 1L
    }

    fun intern(value: String?): String? {
        if (value == null) return null
        return stringPool.getOrPut(value) { value }
    }

    fun courseKey(
        courseNumber: String?,
        instructor: String?,
    ): String = "${courseNumber.orEmpty().trim()}\u0000${instructor.orEmpty().trim()}"
}

class LectureSnapshot(
    val courseTitle: String?,
    val instructor: String?,
    val credit: Int?,
    val remark: String?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val categoryPre2025: String?,
    val classTimeKey: String,
)

class BatchWriter(
    private val jdbc: JdbcTemplate,
    private val table: String,
    private val columns: List<String>,
    private val batchSize: Int = 1_000,
    private val parent: BatchWriter? = null,
) : AutoCloseable {
    private val sql =
        "INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) " +
            "VALUES (${columns.joinToString(",") { "?" }})"
    private val buffer = ArrayList<Array<Any?>>(batchSize)

    var written: Long = 0
        private set

    fun add(vararg values: Any?) {
        require(values.size == columns.size) {
            "$table: 컬럼 ${columns.size}개인데 값 ${values.size}개가 왔다"
        }
        @Suppress("UNCHECKED_CAST")
        buffer.add(values as Array<Any?>)
        if (buffer.size >= batchSize) flush()
    }

    fun flush() {
        if (buffer.isEmpty()) return
        parent?.flush()
        jdbc.batchUpdate(
            sql,
            object : BatchPreparedStatementSetter {
                override fun getBatchSize() = buffer.size

                override fun setValues(
                    ps: PreparedStatement,
                    i: Int,
                ) {
                    buffer[i].forEachIndexed { index, value -> ps.setObject(index + 1, value) }
                }
            },
        )
        written += buffer.size
        buffer.clear()
    }

    override fun close() = flush()
}

class IdSequence(
    start: Long = 1L,
) {
    private var next = start

    fun next(): Long = next++

    fun peek(): Long = next
}

abstract class AbstractMigrationStep(
    protected val jdbc: JdbcTemplate,
    protected val context: MigrationContext,
) : MigrationStep {
    protected val log: Logger = LoggerFactory.getLogger(javaClass)

    protected fun writer(
        table: String,
        columns: List<String>,
        parent: BatchWriter? = null,
    ) = BatchWriter(jdbc, table, columns, parent = parent)

    protected fun alignAutoIncrement(
        table: String,
        next: Long,
    ) {
        jdbc.execute("ALTER TABLE `$table` AUTO_INCREMENT = ${maxOf(next, 1L)}")
    }
}

object MigrationSupport {
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    object ResolutionReasons {
        const val TIMETABLE_USER_MISSING = "사용자가 없는 시간표를 제외"
        const val BOOKMARK_USER_MISSING = "사용자가 없는 북마크 항목을 제외"
        const val BOOKMARK_LECTURE_MISSING = "강의를 찾을 수 없는 북마크 항목을 제외"
        const val BOOKMARK_LECTURE_MERGED = "하나로 합쳐진 강의를 가리키는 북마크 항목을 제외"
        const val VACANCY_USER_MISSING = "사용자가 없는 빈자리 알림을 제외"
        const val VACANCY_LECTURE_MISSING = "강의를 찾을 수 없는 빈자리 알림을 제외"
        const val VACANCY_DUPLICATE = "같은 사용자·강의의 빈자리 알림이 중복되어 제외"
        const val DIARY_USER_MISSING = "사용자가 없는 강의 일기장 기록을 제외"
        const val LOCAL_ID_DUPLICATE = "같은 아이디를 쓰는 활성 계정이 여럿이라 로컬 로그인 수단을 제거"
        const val VERIFIED_EMAIL_DUPLICATE = "같은 이메일이 인증된 활성 계정이 여럿이라 인증 상태를 해제"
        const val SOCIAL_AUTH_DUPLICATE = "같은 소셜 계정을 쓰는 활성 계정이 여럿이라 소셜 로그인 수단을 제거"
        const val EV_COURSE_DUPLICATE = "구 ev course 중복을 하나로 합쳐 이관"
        const val LECTURE_DUPLICATE = "같은 (연도, 학기, 교과목번호, 분반)의 강의가 중복되어 하나로 합침"
        const val EV_LECTURE_DUPLICATE = "하나로 합쳐진 구 ev course의 같은 학기 강의가 중복되어 하나로 합침"
        const val THEME_USER_MISSING = "사용자가 없는 테마를 제외"
        const val PUBLISHED_THEME_USER_MISSING = "사용자가 없는 공개 테마를 제외"
        const val PUBLISHED_THEME_ARCHIVED = "기존 다운로드 내용을 비공개 스냅샷으로 보존"
        const val THEME_DOWNLOAD_MERGED = "동일한 온라인 테마의 중복 다운로드를 합침"
        const val THEME_MISSING = "테마를 찾을 수 없어 기본 테마로 대체"
        const val TIMETABLE_TITLE_DUPLICATE = "같은 학기에 제목이 중복되어 번호를 붙임"
        const val TIMETABLE_LECTURE_USER_MISSING = "사용자가 없는 시간표의 강의를 제외"
        const val DEVICE_USER_MISSING = "사용자가 없는 기기를 제외"
        const val DEVICE_REGISTRATION_DUPLICATE = "같은 FCM 등록 토큰의 활성 기기가 중복되어 이전 항목을 비활성화"
        const val DEVICE_REGISTRATION_MISSING = "FCM 등록 토큰이 없는 기기를 비활성화"
        const val PUSH_PREFERENCE_USER_MISSING = "사용자가 없는 푸시 설정을 제외"
        const val FRIEND_USER_MISSING = "사용자를 찾을 수 없는 친구 관계를 제외"
        const val FRIEND_DUPLICATE = "같은 사용자 쌍의 친구 관계가 중복되어 하나만 남김"
        const val NOTIFICATION_USER_MISSING = "사용자가 없는 알림을 제외"
        const val EVALUATION_LIKE_USER_MISSING = "사용자가 없는 강의평 공감을 제외"
        const val EVALUATION_REPORT_USER_MISSING = "사용자가 없는 강의평 신고를 제외"
        const val LEGACY_TOKEN_AMBIGUOUS = "같은 구 토큰을 가진 활성 계정이 여럿이라 토큰을 이관하지 않음"
        const val PALETTE_INDEX_OUT_OF_RANGE = "범위 밖의 구 팔레트 번호를 정규화"
        const val REMINDER_TIMETABLE_LECTURE_MISSING = "시간표 강의를 찾을 수 없는 리마인더를 제외"
        const val DEEPLINK_TARGET_MISSING = "대상을 찾을 수 없는 알림 deeplink를 제거"
        const val PRIMARY_TIMETABLE_DUPLICATE = "같은 학기의 대표 시간표가 여럿이라 가장 최근에 수정한 시간표만 대표로 남김"
        const val INVALID_CUSTOM_COLOR = "올바르지 않은 사용자 지정 색상 대신 팔레트 색상을 사용"
    }

    fun truncate(
        jdbc: JdbcTemplate,
        tables: List<String>,
    ) {
        jdbc.execute(
            ConnectionCallback { connection ->
                connection.createStatement().use { statement ->
                    val foreignKeyChecks =
                        statement.executeQuery("SELECT @@SESSION.FOREIGN_KEY_CHECKS").use { result ->
                            check(result.next())
                            result.getInt(1)
                        }
                    statement.execute("SET FOREIGN_KEY_CHECKS = 0")
                    try {
                        tables.forEach { statement.execute("TRUNCATE TABLE `$it`") }
                    } finally {
                        statement.execute("SET FOREIGN_KEY_CHECKS = $foreignKeyChecks")
                    }
                }
            },
        )
    }

    fun requireEmpty(
        jdbc: JdbcTemplate,
        tables: List<String>,
    ) {
        tables.forEach { table ->
            val filter = if (table == "timetable_theme") " WHERE builtin_code IS NULL" else ""
            val count = jdbc.queryForObject("SELECT COUNT(*) FROM `$table`$filter", Long::class.java)!!
            check(count == 0L) {
                "$table 에 이미 $count 행이 있다. 부분 재실행은 행을 중복시키므로 --truncate 로 비우고 다시 실행한다"
            }
        }
    }

    fun toLocalDate(instant: Instant): LocalDate = instant.atZone(KST).toLocalDate()
}

fun Document.oid(key: String): String? =
    when (val value = get(key)) {
        is ObjectId -> value.toHexString()
        is String -> value.takeIf { it.length == 24 }
        else -> null
    }

fun Document.requireOid(key: String): String = oid(key) ?: missing(key)

fun Document.id(): String = oid("_id") ?: missing("_id")

private fun Document.missing(key: String): Nothing = error("$key 없는 문서: ${oid("_id") ?: toJson()}")

fun Document.str(key: String): String? = get(key)?.takeIf { it !is Document && it !is List<*> }?.toString()

fun Document.requireStr(key: String): String = str(key) ?: missing(key)

fun Document.int(key: String): Int? = (get(key) as? Number)?.toInt()

fun Document.requireInt(key: String): Int = int(key) ?: missing(key)

fun Document.long(key: String): Long? = (get(key) as? Number)?.toLong()

fun Document.dbl(key: String): Double? = (get(key) as? Number)?.toDouble()

fun Document.bool(key: String): Boolean = get(key) as? Boolean ?: false

fun Document.doc(key: String): Document? = get(key) as? Document

@Suppress("UNCHECKED_CAST")
fun Document.docs(key: String): List<Document> = (get(key) as? List<*>)?.filterIsInstance<Document>() ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Document.strings(key: String): List<String> = (get(key) as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

fun Document.oids(key: String): List<String> =
    (get(key) as? List<*>)?.mapNotNull {
        when (it) {
            is ObjectId -> it.toHexString()
            is String -> it
            else -> null
        }
    } ?: emptyList()

fun Document.instant(key: String): Instant? =
    when (val value = get(key)) {
        is Date -> value.toInstant()
        is Number -> Instant.ofEpochMilli(value.toLong())
        is String -> runCatching { Instant.parse(value) }.getOrNull()
        else -> null
    }

fun Instant?.orNow(): Instant = this ?: Instant.now()

fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)
