package com.wafflestudio.snutt.migration.rehearsal.mask

import org.springframework.jdbc.core.JdbcTemplate

/**
 * EV MySQL DB를 제자리에서 마스킹한다. 테이블·컬럼이 [MaskingPlan.EV_COLUMNS]와 다르면(검토하지 않은 데이터) 멈춘다.
 * id, user_id(v1 ObjectId 문자열), 점수, 시각은 그대로다.
 */
class EvMasker(
    private val jdbc: JdbcTemplate,
    private val pseudonymizer: Pseudonymizer,
    private val report: MaskReport,
) {
    fun run() {
        checkSchema()
        MaskingPlan.EV_TABLES.forEach { (table, columns) ->
            columns.forEach { column -> maskColumn(table, column) }
        }
    }

    private fun checkSchema() {
        val columns = LinkedHashMap<String, MutableSet<String>>()
        jdbc.query(
            "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()",
        ) { rs -> columns.getOrPut(rs.getString(1)) { HashSet() } += rs.getString(2) }
        val unknownTables = columns.keys - MaskingPlan.EV_TABLES.keys
        check(unknownTables.isEmpty()) { "검토하지 않은 EV 테이블이 있다: $unknownTables. MaskingPlan.EV_TABLES에 넣고 개인정보 컬럼을 정한다" }
        columns.forEach { (table, actual) ->
            val known = MaskingPlan.EV_COLUMNS[table] ?: return@forEach
            val unknown = actual - known
            check(unknown.isEmpty()) { "EV $table 에 검토하지 않은 컬럼이 있다: $unknown" }
        }
    }

    private fun maskColumn(
        table: String,
        column: String,
    ) {
        val exists =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Long::class.java,
                table,
            )!! > 0
        if (!exists) return
        val batch = ArrayList<Array<Any>>(BATCH_SIZE)
        var count = 0L

        fun flush() {
            if (batch.isEmpty()) return
            jdbc.batchUpdate("UPDATE `$table` SET `$column` = ? WHERE id = ?", batch)
            batch.clear()
        }
        // 읽는 쪽과 쓰는 쪽 연결을 나누려고 id 구간으로 끊어 읽는다.
        var after = Long.MIN_VALUE
        while (true) {
            val rows =
                jdbc.query(
                    "SELECT id, `$column` FROM `$table` WHERE id > ? ORDER BY id LIMIT $PAGE",
                    { rs, _ -> rs.getLong(1) to rs.getString(2) },
                    after,
                )
            if (rows.isEmpty()) break
            rows.forEach { (id, value) ->
                if (value != null) {
                    batch += arrayOf(pseudonymizer.text("free-text", value), id)
                    count++
                }
                if (batch.size >= BATCH_SIZE) flush()
            }
            flush()
            after = rows.last().first
        }
        report.masked("ev.$table.$column", count)
    }

    companion object {
        private const val BATCH_SIZE = 1_000
        private const val PAGE = 5_000
    }
}
