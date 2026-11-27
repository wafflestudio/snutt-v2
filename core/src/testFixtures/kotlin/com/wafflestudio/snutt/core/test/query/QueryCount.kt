package com.wafflestudio.snutt.core.test.query

import net.ttddyy.dsproxy.ExecutionInfo
import net.ttddyy.dsproxy.QueryInfo
import net.ttddyy.dsproxy.QueryType
import net.ttddyy.dsproxy.listener.QueryExecutionListener
import net.ttddyy.dsproxy.listener.QueryUtils
import net.ttddyy.dsproxy.proxy.ParameterSetOperation
import net.ttddyy.dsproxy.support.ProxyDataSource
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder
import org.junit.jupiter.api.Assertions.fail
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.sql.PreparedStatement
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

data class QueryCounts(
    val select: Int = 0,
    val insert: Int = 0,
    val update: Int = 0,
    val delete: Int = 0,
    val other: Int = 0,
) {
    val total: Int get() = select + insert + update + delete + other
}

/** 실행된 SQL 하나와 그때 바인딩된 파라미터. [bindTo]로 다른 PreparedStatement에 같은 값을 다시 묶을 수 있다(EXPLAIN용). */
class CapturedQuery internal constructor(
    val sql: String,
    private val bindings: List<ParameterSetOperation>,
) {
    /** 바인딩한 값만 순서대로 모은다(실패 메시지용). setNull은 null이다. */
    val parameters: List<Any?>
        get() = bindings.sortedBy { it.args[0] as Int }.map { if (it.method.name == "setNull") null else it.args[1] }

    fun bindTo(statement: PreparedStatement) {
        bindings.forEach { it.method.invoke(statement, *it.args) }
    }

    override fun toString(): String = "$sql $parameters"
}

data class RecordedQueries(
    val queries: List<CapturedQuery>,
) {
    val statements: List<String> get() = queries.map { it.sql }

    val counts: QueryCounts
        get() {
            val byType = statements.groupingBy { QueryUtils.getQueryType(it) }.eachCount()
            return QueryCounts(
                select = byType[QueryType.SELECT] ?: 0,
                insert = byType[QueryType.INSERT] ?: 0,
                update = byType[QueryType.UPDATE] ?: 0,
                delete = byType[QueryType.DELETE] ?: 0,
                other = byType[QueryType.OTHER] ?: 0,
            )
        }
}

/**
 * 블록 안에서 DataSource로 실행된 SQL을 기록한다. JDBC 실행 단위로 센다(배치 실행 1회 = 1개).
 *
 * 스레드와 무관하게 전역으로 모으므로 RANDOM_PORT 서버 스레드가 실행한 쿼리도 잡힌다.
 * 그래서 블록이 도는 동안 다른 스레드가 DB를 쓰지 않아야 결정적이다(테스트 컨텍스트는 @Scheduled를 끈다).
 */
fun <T> recordQueries(block: () -> T): Pair<T, RecordedQueries> {
    check(QueryRecorder.recording.compareAndSet(false, true)) { "recordQueries는 중첩할 수 없다" }
    QueryRecorder.statements.clear()
    try {
        val result = block()
        return result to RecordedQueries(QueryRecorder.statements.toList())
    } finally {
        QueryRecorder.recording.set(false)
    }
}

/**
 * 블록 안 쿼리 수를 종류별로 검증한다. null인 종류는 검증하지 않는다.
 * 예: `assertQueryCount(select = 3, insert = 0) { service.doSomething() }`
 */
fun <T> assertQueryCount(
    select: Int? = null,
    insert: Int? = null,
    update: Int? = null,
    delete: Int? = null,
    total: Int? = null,
    block: () -> T,
): T {
    val (result, recorded) = recordQueries(block)
    val counts = recorded.counts
    val mismatches =
        listOfNotNull(
            mismatch("select", select, counts.select),
            mismatch("insert", insert, counts.insert),
            mismatch("update", update, counts.update),
            mismatch("delete", delete, counts.delete),
            mismatch("total", total, counts.total),
        )
    if (mismatches.isNotEmpty()) {
        fail<Unit>(
            "쿼리 수 불일치: ${mismatches.joinToString()} (실제 $counts)\n" +
                recorded.statements.withIndex().joinToString("\n") { (i, sql) -> "  [${i + 1}] $sql" },
        )
    }
    return result
}

/**
 * 데이터가 늘어도 요청당 쿼리 수가 그대로인지(N+1이 없는지) 검증한다.
 * [request]를 한 번 실행하고, [grow]로 응답에 들어갈 데이터를 늘린 뒤 다시 실행한다. 두 번 모두 종류별 쿼리 수가 기대값과 같아야 한다.
 * 쓰기 쿼리는 기본 0이다. 두 결과를 돌려주므로 호출하는 쪽에서 [grow]가 실제로 응답에 반영됐는지 확인한다(안 하면 검증이 빈다).
 */
fun <T> assertQueryCountStable(
    select: Int,
    insert: Int = 0,
    update: Int = 0,
    delete: Int = 0,
    grow: () -> Unit,
    request: () -> T,
): Pair<T, T> {
    val (before, small) = recordQueries(request)
    grow()
    val (after, large) = recordQueries(request)
    val expected = QueryCounts(select = select, insert = insert, update = update, delete = delete)
    if (small.counts != expected || large.counts != expected) {
        fail<Unit>(
            "쿼리 수 불일치: 기대 $expected, 늘리기 전 ${small.counts}, 늘린 뒤 ${large.counts}\n" +
                "늘리기 전:\n" + small.statements.withIndex().joinToString("\n") { (i, sql) -> "  [${i + 1}] $sql" } +
                "\n늘린 뒤:\n" + large.statements.withIndex().joinToString("\n") { (i, sql) -> "  [${i + 1}] $sql" },
        )
    }
    return before to after
}

private fun mismatch(
    name: String,
    expected: Int?,
    actual: Int,
): String? = if (expected != null && expected != actual) "$name 기대 $expected, 실제 $actual" else null

internal object QueryRecorder {
    val recording = AtomicBoolean(false)
    val statements = CopyOnWriteArrayList<CapturedQuery>()

    val listener =
        object : QueryExecutionListener {
            override fun beforeQuery(
                execInfo: ExecutionInfo,
                queryInfoList: List<QueryInfo>,
            ) = Unit

            override fun afterQuery(
                execInfo: ExecutionInfo,
                queryInfoList: List<QueryInfo>,
            ) {
                if (!recording.get()) return
                queryInfoList.forEach { info ->
                    // batch 실행이면 파라미터 묶음이 여러 개다. EXPLAIN에는 첫 묶음이면 충분하다.
                    statements.add(CapturedQuery(info.query.trim(), info.parametersList.firstOrNull().orEmpty()))
                }
            }
        }
}

/** 컨텍스트의 DataSource를 datasource-proxy로 감싸 [recordQueries]가 쿼리를 볼 수 있게 한다. AbstractIntegrationTest가 import한다. */
@TestConfiguration(proxyBeanMethods = false)
class QueryCountConfig {
    companion object {
        @Bean
        @JvmStatic
        fun queryCountingDataSourcePostProcessor(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    if (bean is DataSource && bean !is ProxyDataSource) {
                        ProxyDataSourceBuilder.create(beanName, bean).listener(QueryRecorder.listener).build()
                    } else {
                        bean
                    }
            }
    }
}
