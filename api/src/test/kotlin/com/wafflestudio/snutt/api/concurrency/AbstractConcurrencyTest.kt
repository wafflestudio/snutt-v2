package com.wafflestudio.snutt.api.concurrency

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.core.test.concurrency.LockContention
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.transaction.PlatformTransactionManager

/** 동시성 테스트 공용 베이스. 어노테이션을 더하지 않으므로 api 컨텍스트를 그대로 공유한다. */
abstract class AbstractConcurrencyTest : AbstractApiIntegrationTest() {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    protected val scenarios by lazy { ConcurrencyScenarios(applicationContext, fixtures) }

    protected val contention by lazy { LockContention(transactionManager, entityManagerFactory) }

    protected companion object {
        /** holder 1 + contender 4. contender가 모두 락을 기다리는 상태를 만들고 Hikari 풀(10)은 넘지 않는다. */
        const val REQUESTS = 5
    }
}
