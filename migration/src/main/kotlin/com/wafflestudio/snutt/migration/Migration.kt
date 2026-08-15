package com.wafflestudio.snutt.migration

import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

interface MigrationStep {
    val name: String

    val tables: List<String>

    fun run()
}

@Component
class MigrationContext {
    val userIds = HashMap<String, Long>(256_000)
    val lectureIds = HashMap<String, Long>(256_000)
    val themeIds = HashMap<String, Long>()
    val diaryClassTypeIds = HashMap<String, Long>()
    val diaryQuestionIds = HashMap<String, Long>()

    val courseIds = HashMap<String, Long>(64_000)

    val lectureSnapshots = HashMap<Long, LectureSnapshot>(256_000)

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

    fun truncate(
        jdbc: JdbcTemplate,
        tables: List<String>,
    ) {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0")
        try {
            tables.forEach { jdbc.execute("TRUNCATE TABLE `$it`") }
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1")
        }
    }

    fun requireEmpty(
        jdbc: JdbcTemplate,
        tables: List<String>,
    ) {
        tables.forEach { table ->
            val count = jdbc.queryForObject("SELECT COUNT(*) FROM `$table`", Long::class.java) ?: 0L
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

fun Document.id(): String = oid("_id") ?: error("_id 없는 문서: ${toJson()}")

fun Document.str(key: String): String? = get(key)?.takeIf { it !is Document && it !is List<*> }?.toString()

fun Document.int(key: String): Int? = (get(key) as? Number)?.toInt()

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
        is java.util.Date -> value.toInstant()
        is Number -> Instant.ofEpochMilli(value.toLong())
        is String -> runCatching { Instant.parse(value) }.getOrNull()
        else -> null
    }

fun Instant?.orNow(): Instant = this ?: Instant.now()

fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)

object Json {
    private val mapper: JsonMapper = JsonMapper.builder().findAndAddModules().build()

    fun write(value: Any?): String? = value?.let { mapper.writeValueAsString(it) }

    fun writeRequired(value: Any): String = mapper.writeValueAsString(value)
}
