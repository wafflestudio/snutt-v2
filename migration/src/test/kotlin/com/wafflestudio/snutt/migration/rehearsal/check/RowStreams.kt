package com.wafflestudio.snutt.migration.rehearsal.check

import com.wafflestudio.snutt.migration.MigrationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.ResultSet
import java.util.HexFormat

/** id 순으로 페이지를 끊어 읽는 행 스트림. 운영 크기 테이블도 메모리에 다 올리지 않는다. */
fun JdbcTemplate.rowsById(
    /** `?` 하나(직전 페이지의 마지막 id)를 받고 `ORDER BY <id> LIMIT`까지 쓴 SQL. */
    pagedSql: String,
    mapper: (ResultSet) -> Pair<Long, String>?,
): Sequence<Pair<Long, String>> =
    sequence {
        var after = Long.MIN_VALUE
        while (true) {
            val raw = query(pagedSql, { rs, _ -> rs.getLong(1) to mapper(rs) }, after)
            if (raw.isEmpty()) break
            raw.forEach { (_, row) -> if (row != null) yield(row) }
            after = raw.last().first
        }
    }

class StreamComparison(
    val name: String,
    val v1Rows: Long,
    val v2Rows: Long,
    val v1Digest: String,
    val v2Digest: String,
    val mismatchedIds: List<Long>,
    val mismatchCount: Long,
) {
    val passed get() = v1Digest == v2Digest && mismatchCount == 0L
}

/** 두 id 순 스트림의 SHA-256 지문을 만들고, 다르면 어긋난 id를 모은다. */
fun compareStreams(
    name: String,
    v1: Sequence<Pair<Long, String>>,
    v2: Sequence<Pair<Long, String>>,
): StreamComparison {
    val d1 = MessageDigest.getInstance("SHA-256")
    val d2 = MessageDigest.getInstance("SHA-256")
    val a = v1.iterator()
    val b = v2.iterator()
    var x = if (a.hasNext()) a.next() else null
    var y = if (b.hasNext()) b.next() else null
    var n1 = 0L
    var n2 = 0L
    var mismatches = 0L
    val ids = mutableListOf<Long>()

    fun mismatch(id: Long) {
        mismatches++
        if (ids.size < 20) ids += id
    }
    while (x != null || y != null) {
        when {
            y == null || (x != null && x.first < y.first) -> {
                d1.update("${x!!.first}\t${x.second}\n".toByteArray())
                n1++
                mismatch(x.first)
                x = if (a.hasNext()) a.next() else null
            }
            x == null || y.first < x.first -> {
                d2.update("${y.first}\t${y.second}\n".toByteArray())
                n2++
                mismatch(y.first)
                y = if (b.hasNext()) b.next() else null
            }
            else -> {
                d1.update("${x.first}\t${x.second}\n".toByteArray())
                d2.update("${y.first}\t${y.second}\n".toByteArray())
                n1++
                n2++
                if (x.second != y.second) mismatch(x.first)
                x = if (a.hasNext()) a.next() else null
                y = if (b.hasNext()) b.next() else null
            }
        }
    }
    val hex = HexFormat.of()
    return StreamComparison(name, n1, n2, hex.formatHex(d1.digest()), hex.formatHex(d2.digest()), ids, mismatches)
}

/**
 * id를 그대로 옮기는 EV 테이블(강의평, 공감, 신고, 검색 태그)을 행 단위 지문으로 대조한다.
 * v1 쪽 행은 이관 규칙(내용 trim, 과목 id 승계, 사용자 없는 공감·신고 제외)을 적용한 기대 모양이다.
 */
class Checksums(
    private val source: SourceIndex,
    private val v2: JdbcTemplate,
    private val context: MigrationContext,
) {
    private val ev = source.ev

    fun run(): List<StreamComparison> = listOf(evaluations(), likes(), reports(), tags())

    private fun ResultSet.cells(vararg columns: String): String = columns.joinToString("\u0001") { getString(it) ?: "\u0000" }

    private fun bool(
        rs: ResultSet,
        column: String,
    ) = rs.getBoolean(column).toString()

    private fun evaluations(): StreamComparison {
        val v1 =
            ev.rowsById(
                "SELECT le.*, sl.lecture_id, sl.year, sl.semester FROM lecture_evaluation le " +
                    "JOIN semester_lecture sl ON sl.id = le.semester_lecture_id WHERE le.id > ? ORDER BY le.id LIMIT $PAGE",
            ) { rs ->
                val course = source.evCourseKey[rs.getLong("lecture_id")]?.let(source.evCourseIdOfKey::get)
                val user = context.userIds[rs.getString("user_id")]
                rs.getLong("id") to
                    listOf(
                        course.toString(),
                        user.toString(),
                        rs.getString("year"),
                        rs.getString("semester"),
                        rs.getString("content").trim(),
                        rs.cells("grade_satisfaction", "teaching_skill", "gains", "life_balance", "rating", "created_at", "updated_at"),
                        bool(rs, "is_hidden"),
                        bool(rs, "is_reported"),
                        bool(rs, "from_snuev"),
                    ).joinToString("\u0001")
            }
        val v2Rows =
            v2.rowsById("SELECT * FROM evaluation WHERE id > ? ORDER BY id LIMIT $PAGE") { rs ->
                rs.getLong("id") to
                    listOf(
                        rs.getLong("course_id").toString(),
                        rs.getObject("user_id")?.toString() ?: "null",
                        rs.getString("year"),
                        rs.getString("semester"),
                        rs.getString("content"),
                        rs.cells("grade_satisfaction", "teaching_skill", "gains", "life_balance", "rating", "created_at", "updated_at"),
                        bool(rs, "is_hidden"),
                        bool(rs, "is_reported"),
                        bool(rs, "from_snuev"),
                    ).joinToString("\u0001")
            }
        return compareStreams("evaluation", v1, v2Rows)
    }

    private fun likes(): StreamComparison =
        compareStreams(
            "evaluation_like",
            ev.rowsById("SELECT * FROM evaluation_like WHERE id > ? ORDER BY id LIMIT $PAGE") { rs ->
                val user = context.userIds[rs.getString("user_id")] ?: return@rowsById null
                rs.getLong("id") to "${rs.getLong("lecture_evaluation_id")}\u0001$user\u0001${rs.cells("created_at", "updated_at")}"
            },
            v2.rowsById("SELECT * FROM evaluation_like WHERE id > ? ORDER BY id LIMIT $PAGE") { rs ->
                rs.getLong("id") to
                    "${rs.getLong("evaluation_id")}\u0001${rs.getLong("user_id")}\u0001${rs.cells("created_at", "updated_at")}"
            },
        )

    private fun reports(): StreamComparison =
        compareStreams(
            "evaluation_report",
            ev.rowsById("SELECT * FROM evaluation_report WHERE id > ? ORDER BY id LIMIT $PAGE") { rs ->
                val user = context.userIds[rs.getString("user_id")] ?: return@rowsById null
                rs.getLong("id") to
                    "${rs.getLong("lecture_evaluation_id")}\u0001$user\u0001${rs.cells("content")}\u0001${bool(rs, "is_hidden")}"
            },
            v2.rowsById("SELECT * FROM evaluation_report WHERE id > ? ORDER BY id LIMIT $PAGE") { rs ->
                rs.getLong("id") to
                    "${rs.getLong("evaluation_id")}\u0001${rs.getLong("user_id")}\u0001${rs.cells("content")}\u0001${bool(rs, "is_hidden")}"
            },
        )

    private fun tags(): StreamComparison =
        compareStreams(
            "legacy_search_tag",
            ev.rowsById(
                "SELECT t.id, g.name AS group_name, g.ordering AS group_ordering, g.color AS group_color, t.name, t.ordering, " +
                    "t.int_value, t.string_value FROM tag t JOIN tag_group g ON g.id = t.tag_group_id " +
                    "WHERE g.name <> 'main' AND t.id > ? ORDER BY t.id LIMIT $PAGE",
            ) { rs -> rs.getLong("id") to rs.cells(*TAG_COLUMNS) },
            v2.rowsById("SELECT * FROM legacy_search_tag WHERE id > ? ORDER BY id LIMIT $PAGE") { rs ->
                rs.getLong("id") to
                    rs.cells(*TAG_COLUMNS)
            },
        )

    companion object {
        private const val PAGE = 5_000
        private val TAG_COLUMNS = arrayOf("group_name", "group_ordering", "group_color", "name", "ordering", "int_value", "string_value")
    }
}
