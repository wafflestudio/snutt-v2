package com.wafflestudio.snutt.batch.perf

import com.wafflestudio.snutt.batch.AbstractBatchIntegrationTest
import com.wafflestudio.snutt.core.test.PerfTest
import com.wafflestudio.snutt.core.test.perf.PerfDatabase
import com.wafflestudio.snutt.core.test.perf.PerfDataset
import com.wafflestudio.snutt.core.test.perf.QueryPlan

/** batch 리포지토리 쿼리의 실행계획 테스트 베이스. batch 통합테스트와 같은 컨텍스트를 쓴다. 동작은 api `AbstractPerfTest`와 같다. */
@PerfTest
abstract class AbstractBatchPerfTest : AbstractBatchIntegrationTest() {
    protected val perf: PerfDataset get() = PerfDatabase.dataset

    protected fun explainAll(block: () -> Unit): List<QueryPlan> =
        com.wafflestudio.snutt.core.test.perf
            .explainAll(block)

    protected fun explainOne(block: () -> Unit): QueryPlan =
        com.wafflestudio.snutt.core.test.perf
            .explainOne(block)
}
