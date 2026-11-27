package com.wafflestudio.snutt.core.test.stress

import com.wafflestudio.snutt.core.test.IntegrationContainers
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * 테스트 쪽에서 MySQL을 보는 별도 연결. 애플리케이션 Hikari 풀(운영과 같은 크기)을 테스트 조회가 빼앗지 않도록 root 계정의 작은 풀을 따로 둔다.
 * InnoDB 락 지표(INNODB_METRICS)와 `SHOW ENGINE INNODB STATUS`는 PROCESS 권한이 필요해 root로 본다.
 */
object MySqlProbe {
    private val dataSource: HikariDataSource by lazy {
        val mysql = IntegrationContainers.mysql
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = mysql.jdbcUrl
                username = "root"
                password = mysql.password
                maximumPoolSize = 4
                poolName = "stress-probe"
            },
        )
    }

    /** 대상 선택·불변식 검증용 조회. 애플리케이션 풀과 별개다. */
    val jdbc: JdbcTemplate by lazy { JdbcTemplate(dataSource) }

    data class LockCounters(
        val deadlocks: Long,
        val lockTimeouts: Long,
    ) {
        operator fun minus(other: LockCounters) = LockCounters(deadlocks - other.deadlocks, lockTimeouts - other.lockTimeouts)
    }

    /** 서버가 시작한 뒤 누적된 데드락·lock wait timeout 수. 차이로 시나리오·반복별 발생 수를 센다. */
    fun lockCounters(): LockCounters {
        val counts =
            jdbc
                .queryForList(
                    "SELECT NAME, COUNT FROM information_schema.INNODB_METRICS WHERE NAME IN ('lock_deadlocks', 'lock_timeouts')",
                ).associate { it["NAME"] as String to (it["COUNT"] as Number).toLong() }
        return LockCounters(counts.getValue("lock_deadlocks"), counts.getValue("lock_timeouts"))
    }

    /** InnoDB가 마지막으로 감지한 데드락의 두 트랜잭션과 잡은·기다린 락. 없으면 null. */
    fun latestDeadlock(): String? {
        val status = jdbc.queryForMap("SHOW ENGINE INNODB STATUS")["Status"] as String
        val start = status.indexOf("LATEST DETECTED DEADLOCK")
        if (start < 0) return null
        val end = status.indexOf("\n------------\nTRANSACTIONS", start).takeIf { it > 0 } ?: status.length
        return status.substring(start, end).trim()
    }

    /** 리포트 머리말에 남길 설정값. 애플리케이션 [appDataSource]의 Hikari 설정과 서버 변수를 읽는다. */
    fun environment(appDataSource: DataSource): Map<String, String> {
        val hikari = runCatching { appDataSource.unwrap(HikariDataSource::class.java) }.getOrNull()
        val variables =
            jdbc
                .queryForList(
                    "SELECT @@innodb_lock_wait_timeout AS lock_wait_timeout, @@transaction_isolation AS isolation, " +
                        "@@max_connections AS max_connections, @@innodb_deadlock_detect AS deadlock_detect, VERSION() AS version",
                ).single()
        return linkedMapOf(
            "MySQL" to variables["version"].toString(),
            "innodb_lock_wait_timeout(s)" to variables["lock_wait_timeout"].toString(),
            "transaction_isolation" to variables["isolation"].toString(),
            "innodb_deadlock_detect" to variables["deadlock_detect"].toString(),
            "max_connections" to variables["max_connections"].toString(),
            "Hikari maximumPoolSize" to (hikari?.maximumPoolSize?.toString() ?: "알 수 없음"),
            "Hikari connectionTimeout(ms)" to (hikari?.connectionTimeout?.toString() ?: "알 수 없음"),
        )
    }
}
