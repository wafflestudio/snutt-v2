package com.wafflestudio.snutt.migration.rehearsal.check

import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.util.HexFormat

/**
 * v2 DB 전체의 테이블별 지문(행 수 + 내용 SHA-256). 같은 입력으로 두 번 이관한 결과가 같은지(멱등성) 본다.
 *
 * 이관이 실행 시각(`Instant.now()`)으로 채우는 열은 뺀다: `created_at`, `updated_at`(원본에 없을 때),
 * `last_login_at`·`notification_checked_at`(원본에 없을 때 now로 대신함), `recent_notified_at`. 생성 열(GENERATED)도 뺀다.
 */
object Fingerprint {
    private val TIME_COLUMNS = setOf("created_at", "updated_at", "last_login_at", "notification_checked_at", "recent_notified_at")

    class TableFingerprint(
        val table: String,
        val rows: Long,
        val digest: String,
    )

    fun of(jdbc: JdbcTemplate): Map<String, TableFingerprint> {
        val columns = LinkedHashMap<String, MutableList<String>>()
        jdbc.query(
            "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                "AND TABLE_NAME <> 'flyway_schema_history' AND EXTRA NOT LIKE '%GENERATED%' ORDER BY TABLE_NAME, ORDINAL_POSITION",
        ) { rs -> columns.getOrPut(rs.getString(1)) { mutableListOf() } += rs.getString(2) }
        val keys = HashMap<String, String>()
        jdbc.query(
            "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'PRIMARY'",
        ) { rs -> keys[rs.getString(1)] = rs.getString(2) }
        return columns.mapValues { (table, all) ->
            val key = keys.getValue(table)
            val compared = all.filterNot { it in TIME_COLUMNS }
            val digest = MessageDigest.getInstance("SHA-256")
            var rows = 0L
            val select = compared.joinToString(",") { "`$it`" }
            jdbc
                .rowsById("SELECT `$key`, $select FROM `$table` WHERE `$key` > ? ORDER BY `$key` LIMIT 5000") { rs ->
                    rs.getLong(1) to (2..compared.size + 1).joinToString("\u0001") { rs.getString(it) ?: "\u0000" }
                }.forEach { (_, row) ->
                    digest.update(row.toByteArray())
                    digest.update('\n'.code.toByte())
                    rows++
                }
            TableFingerprint(table, rows, HexFormat.of().formatHex(digest.digest()))
        }
    }
}
