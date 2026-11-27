package com.wafflestudio.snutt.core.test.query

import net.ttddyy.dsproxy.ExecutionInfo
import net.ttddyy.dsproxy.QueryInfo
import net.ttddyy.dsproxy.QueryType
import net.ttddyy.dsproxy.listener.QueryExecutionListener
import net.ttddyy.dsproxy.listener.QueryUtils
import net.ttddyy.dsproxy.support.ProxyDataSource
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder
import org.junit.jupiter.api.Assertions.fail
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
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

data class RecordedQueries(
    val statements: List<String>,
) {
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

private fun mismatch(
    name: String,
    expected: Int?,
    actual: Int,
): String? = if (expected != null && expected != actual) "$name 기대 $expected, 실제 $actual" else null

internal object QueryRecorder {
    val recording = AtomicBoolean(false)
    val statements = CopyOnWriteArrayList<String>()

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
                queryInfoList.forEach { statements.add(it.query.trim()) }
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
