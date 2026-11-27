package com.wafflestudio.snutt.core.test.fault

import com.wafflestudio.snutt.core.test.stress.MySqlProbe
import java.util.concurrent.atomic.AtomicInteger

/**
 * 트리거가 던질 오류. [DEADLOCK]은 MySQL 데드락(1213)과 같은 코드라 Spring이 `PessimisticLockingFailureException`으로,
 * [DUPLICATE_KEY]는 unique 위반(1062)이라 `DataIntegrityViolationException`으로 바꾼다.
 */
enum class DbFault(
    val sqlState: String,
    val errorCode: Int,
) {
    ERROR("45000", 1644),
    DEADLOCK("40001", 1213),
    DUPLICATE_KEY("23000", 1062),
}

/**
 * DB 경계에서 쓰기를 실패시킨다. root 연결로 `BEFORE` 트리거를 만들어 [condition]에 맞는 행의 [event]가 [fault]로 실패하게 하고,
 * 닫으면 트리거를 지운다. 프로덕션 코드에 테스트 훅을 두지 않고 "이 트랜잭션이 여기서 죽는다"를 만든다(프로세스 종료·네트워크 단절처럼
 * 커밋 전에 끝나는 장애와 DB 상태가 같다).
 *
 * ```kotlin
 * DbFaults.failing("timetable", "UPDATE", "NEW.id = ${timetable.id}").use { sync(rows) }
 * ```
 *
 * 트리거는 테이블 초기화(TRUNCATE)로 지워지지 않으므로 반드시 `use`로 닫는다.
 */
object DbFaults {
    private val sequence = AtomicInteger()

    fun failing(
        table: String,
        event: String,
        condition: String = "TRUE",
        fault: DbFault = DbFault.ERROR,
    ): AutoCloseable {
        require(event in setOf("INSERT", "UPDATE", "DELETE")) { event }
        val name = "test_fault_${table}_${event.lowercase()}_${sequence.incrementAndGet()}"
        MySqlProbe.jdbc.execute(
            """
            CREATE TRIGGER $name BEFORE $event ON `$table` FOR EACH ROW
            BEGIN
                IF $condition THEN
                    SIGNAL SQLSTATE '${fault.sqlState}' SET MYSQL_ERRNO = ${fault.errorCode}, MESSAGE_TEXT = 'injected fault: $name';
                END IF;
            END
            """.trimIndent(),
        )
        return AutoCloseable { MySqlProbe.jdbc.execute("DROP TRIGGER IF EXISTS $name") }
    }
}
