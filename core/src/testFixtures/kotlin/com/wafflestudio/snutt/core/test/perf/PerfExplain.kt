package com.wafflestudio.snutt.core.test.perf

import com.wafflestudio.snutt.core.test.query.recordQueries
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * [block]에서 실행된 SELECT를 실행 순서대로 perf 스키마([PerfDatabase])에서 EXPLAIN한다. [block]은 평소처럼 기본 스키마(비어 있다)에서
 * 돌고, 그때 나간 SQL과 파라미터만 가져간다. 모듈별 perf 베이스(api `AbstractPerfTest`, batch `AbstractBatchPerfTest`)가 쓴다.
 */
fun explainAll(block: () -> Unit): List<QueryPlan> {
    PerfDatabase.dataset
    val (_, recorded) = recordQueries(block)
    return recorded.queries
        .filter { it.sql.trimStart().let { sql -> sql.startsWith("select", ignoreCase = true) || sql.startsWith("(") } }
        .map { explain(it) }
}

/** [block]이 SELECT를 정확히 하나 실행해야 한다. */
fun explainOne(block: () -> Unit): QueryPlan {
    val plans = explainAll(block)
    assertEquals(1, plans.size, "SELECT가 하나여야 한다: ${plans.map { it.query.sql }}")
    return plans.single()
}
