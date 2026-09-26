package com.wafflestudio.snutt.core.common.transaction

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

private val log = LoggerFactory.getLogger("com.wafflestudio.snutt.core.common.transaction.AfterCommit")

fun afterCommit(action: () -> Unit) {
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit() {
                runCatching(action).onFailure { log.error("커밋 뒤 작업 실패", it) }
            }
        },
    )
}
