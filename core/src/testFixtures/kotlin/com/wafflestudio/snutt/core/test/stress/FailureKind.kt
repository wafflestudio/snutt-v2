package com.wafflestudio.snutt.core.test.stress

import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLTransactionRollbackException
import java.sql.SQLTransientConnectionException

/** stress 실패 종류. 서버가 500을 낸 원인(예외 체인)이나 클라이언트가 본 결과로 정한다. */
enum class FailureKind(
    val label: String,
) {
    DEADLOCK("데드락"),
    LOCK_WAIT_TIMEOUT("lock wait timeout"),
    POOL_TIMEOUT("커넥션 풀 획득 타임아웃"),
    UNIQUE_VIOLATION("unique 제약 위반"),
    FOREIGN_KEY_VIOLATION("외래 키 제약 위반"),
    OTHER_SERVER_ERROR("기타 서버 오류"),
    SERVER_5XX("5xx 응답"),
    UNEXPECTED_OUTCOME("허용하지 않은 응답"),
    CLIENT_TIMEOUT("요청이 끝나지 않음"),
    INVARIANT("불변식 위반"),
    ;

    companion object {
        /** 예외 체인을 따라가며 DB 쪽 원인을 찾는다. 찾지 못하면 [OTHER_SERVER_ERROR]다. */
        fun classify(error: Throwable): FailureKind {
            val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(20).toList()
            val messages = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
            return when {
                messages.contains("Deadlock found", ignoreCase = true) -> DEADLOCK
                messages.contains("Lock wait timeout exceeded", ignoreCase = true) -> LOCK_WAIT_TIMEOUT
                chain.any { it is SQLTransientConnectionException } || messages.contains("Connection is not available") -> POOL_TIMEOUT
                chain.any { it is SQLTransactionRollbackException } -> DEADLOCK
                messages.contains("foreign key constraint", ignoreCase = true) -> FOREIGN_KEY_VIOLATION
                chain.any { it is SQLIntegrityConstraintViolationException } || messages.contains("Duplicate entry") -> UNIQUE_VIOLATION
                else -> OTHER_SERVER_ERROR
            }
        }

        /** 리포트에 남길 한 줄 요약. 가장 안쪽 원인의 메시지를 쓴다. */
        fun summarize(error: Throwable): String {
            val root = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(20).last()
            return "${error.javaClass.simpleName} <- ${root.javaClass.simpleName}: ${root.message?.lineSequence()?.firstOrNull()}"
        }
    }
}
