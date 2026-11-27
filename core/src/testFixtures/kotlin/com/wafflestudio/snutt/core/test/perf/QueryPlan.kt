package com.wafflestudio.snutt.core.test.perf

import com.wafflestudio.snutt.core.test.query.CapturedQuery
import org.junit.jupiter.api.Assertions.fail
import java.sql.Connection

/** 전통 형식 EXPLAIN 한 행. [table]은 SQL의 별칭을 실제 테이블 이름으로 바꾼 값이다(`<derived2>`, `<union1,2>` 같은 임시 테이블은 그대로). */
data class PlanRow(
    val id: Int?,
    val selectType: String,
    val table: String,
    val alias: String,
    val type: String?,
    val possibleKeys: String?,
    val key: String?,
    val rows: Long?,
    val extra: String,
) {
    val isTemporaryTable: Boolean get() = table.startsWith("<")

    override fun toString(): String = "id=$id $selectType $table($alias) type=$type key=$key rows=$rows extra=[$extra]"
}

/**
 * 캡처한 SQL 하나의 실행계획. [explain]으로 만들고 assert 메서드를 이어 부른다. 실패 메시지에 SQL, 파라미터, 표 형식과 TREE 형식 계획이 모두 찍힌다.
 *
 * ```
 * explain(captured)
 *     .assertKey("timetable", "idx_timetable_user_semester")
 *     .assertNoFullScan()
 *     .assertNoFilesort()
 *     .assertRowsAtMost("timetable", 1_000)
 * ```
 */
class QueryPlan(
    val query: CapturedQuery,
    val rows: List<PlanRow>,
    val tree: String,
) {
    /** 실제 테이블 하나의 계획 행. 같은 테이블이 여러 번 나오면(UNION 갈래, 서브쿼리) [rowsOf]를 쓴다. */
    fun row(table: String): PlanRow = rowsOf(table).singleOrNull() ?: failWith("테이블 $table 의 계획 행이 정확히 하나가 아니다: ${rowsOf(table)}")

    fun rowsOf(table: String): List<PlanRow> = rows.filter { it.table == table }

    /** [table]의 모든 계획 행이 [keys] 중 하나의 인덱스를 쓴다. 앞부분이 같은 인덱스가 여럿이면 옵티마이저가 아무거나 고를 수 있어 함께 적는다. */
    fun assertKey(
        table: String,
        vararg keys: String,
    ): QueryPlan {
        val targets = rowsOf(table)
        if (targets.isEmpty() ||
            targets.any { it.key !in keys }
        ) {
            failWith("$table 이(가) ${keys.toList()} 중 하나를 써야 한다. 실제: ${targets.map { it.key }}")
        }
        return this
    }

    /** [table]의 access type이 [types] 중 하나다(const, eq_ref, ref, range, index ...). */
    fun assertAccess(
        table: String,
        vararg types: String,
    ): QueryPlan {
        val targets = rowsOf(table)
        if (targets.isEmpty() ||
            targets.any { it.type !in types }
        ) {
            failWith("$table access type이 ${types.toList()} 중 하나여야 한다. 실제: ${targets.map { it.type }}")
        }
        return this
    }

    /** 실제 테이블에 access type ALL(풀 스캔)이 없다. 임시 테이블(`<derived>`·`<union>`)은 결과를 다시 읽을 뿐이라 제외한다. */
    fun assertNoFullScan(vararg allowed: String): QueryPlan {
        val scans = rows.filter { !it.isTemporaryTable && it.type == "ALL" && it.table !in allowed }
        if (scans.isNotEmpty()) failWith("풀 스캔이 있다: $scans")
        return this
    }

    /** filesort가 없다. [allowed]에 테이블 이름을 주면 그 행은 허용한다(예: UNION 결과를 다시 정렬하는 `<derived2>`). */
    fun assertNoFilesort(vararg allowed: String): QueryPlan {
        val sorts = rows.filter { "Using filesort" in it.extra && it.table !in allowed }
        if (sorts.isNotEmpty()) failWith("filesort가 있다: $sorts")
        return this
    }

    fun assertNoTemporary(vararg allowed: String): QueryPlan {
        val temporaries = rows.filter { "Using temporary" in it.extra && it.table !in allowed }
        if (temporaries.isNotEmpty()) failWith("임시 테이블(Using temporary)이 있다: $temporaries")
        return this
    }

    /** [table]의 예상 rows가 모든 행에서 [max] 이하다. 통계는 샘플링이라 실제 기대값보다 넉넉히 잡는다. */
    fun assertRowsAtMost(
        table: String,
        max: Long,
    ): QueryPlan {
        val targets = rowsOf(table)
        if (targets.isEmpty() ||
            targets.any { (it.rows ?: 0) > max }
        ) {
            failWith("$table 예상 rows가 $max 이하여야 한다. 실제: ${targets.map { it.rows }}")
        }
        return this
    }

    fun failWith(message: String): Nothing {
        fail<Unit>("$message\n$this")
        throw AssertionError(message)
    }

    override fun toString(): String =
        "SQL: ${query.sql}\n파라미터: ${query.parameters}\n계획:\n" + rows.joinToString("\n") { "  $it" } + "\nTREE:\n$tree"
}

/** 캡처한 SQL을 같은 파라미터로 [connection](perf 스키마)에서 EXPLAIN한다. SELECT·UPDATE·DELETE 모두 된다. */
fun explain(
    query: CapturedQuery,
    connection: Connection = PerfDatabase.connection,
): QueryPlan {
    val aliases = tableAliases(query.sql)
    val rows =
        connection.prepareStatement("EXPLAIN FORMAT=TRADITIONAL ${query.sql}").use { statement ->
            query.bindTo(statement)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val alias = rs.getString("table") ?: ""
                        add(
                            PlanRow(
                                id = rs.getObject("id")?.let { (it as Number).toInt() },
                                selectType = rs.getString("select_type"),
                                table = aliases[alias] ?: alias,
                                alias = alias,
                                type = rs.getString("type"),
                                possibleKeys = rs.getString("possible_keys"),
                                key = rs.getString("key"),
                                rows = rs.getObject("rows")?.let { (it as Number).toLong() },
                                extra = rs.getString("Extra") ?: "",
                            ),
                        )
                    }
                }
            }
        }
    val tree =
        connection.prepareStatement("EXPLAIN FORMAT=TREE ${query.sql}").use { statement ->
            query.bindTo(statement)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "" }
        }
    return QueryPlan(query, rows, tree)
}

private val TABLE_REFERENCE =
    Regex("""(?i)\b(?:from|join|update)\s+`?(\w+)`?(?:\s+(?:as\s+)?(?!where|on|join|left|inner|order|group|limit|set|union)(\w+))?""")

/** `from lecture l1_0`, `join course c1_0` 같은 참조에서 별칭 → 테이블 이름을 만든다. 별칭이 없으면 테이블 이름 자체가 키다. */
private fun tableAliases(sql: String): Map<String, String> =
    TABLE_REFERENCE
        .findAll(sql)
        .flatMap { m ->
            val table = m.groupValues[1]
            val alias = m.groupValues[2]
            listOfNotNull(table to table, alias.takeIf { it.isNotEmpty() }?.let { it to table })
        }.toMap()
