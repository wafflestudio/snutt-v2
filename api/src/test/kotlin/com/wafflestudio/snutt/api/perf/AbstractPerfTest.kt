package com.wafflestudio.snutt.api.perf

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.test.PerfTest
import com.wafflestudio.snutt.core.test.perf.PerfDatabase
import com.wafflestudio.snutt.core.test.perf.PerfDataset
import com.wafflestudio.snutt.core.test.perf.PerfSemester
import com.wafflestudio.snutt.core.test.perf.QueryPlan

/**
 * 실행계획(EXPLAIN) 회귀 테스트 베이스. api 통합테스트와 같은 컨텍스트를 쓴다(새 컨텍스트를 띄우지 않는다).
 *
 * 리포지토리 메서드를 평소처럼 호출하면 기본 스키마(비어 있다)에서 실행되고, 그때 나간 SQL과 파라미터를 캡처해
 * 운영 분포 데이터가 든 `snutt_perf` 스키마에서 EXPLAIN한다. 파라미터는 [perf]의 대표 id(극단 사용자, 인기 과목 등)를 넘긴다.
 */
@PerfTest
abstract class AbstractPerfTest : AbstractApiIntegrationTest() {
    protected val perf: PerfDataset get() = PerfDatabase.dataset

    /** [block]에서 실행된 SELECT를 실행 순서대로 EXPLAIN한다. */
    protected fun explainAll(block: () -> Unit): List<QueryPlan> =
        com.wafflestudio.snutt.core.test.perf
            .explainAll(block)

    /** [block]이 SELECT를 정확히 하나 실행해야 한다. */
    protected fun explainOne(block: () -> Unit): QueryPlan =
        com.wafflestudio.snutt.core.test.perf
            .explainOne(block)

    protected val PerfSemester.semesterEnum: Semester get() = Semester.fromValue(semester)
}
