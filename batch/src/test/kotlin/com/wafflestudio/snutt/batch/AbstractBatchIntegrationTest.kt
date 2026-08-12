package com.wafflestudio.snutt.batch

import org.testcontainers.containers.MySQLContainer

// api 모듈과 동일한 패턴: 테스트 클래스마다 전용 DB (서버 1회 기동)
abstract class AbstractBatchIntegrationTest {
    companion object {
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer("mysql:8.4")
                .apply { start() }
                .apply {
                    execInContainer("mysql", "-uroot", "-ptest", "-e", "GRANT ALL PRIVILEGES ON *.* TO 'test'@'%' WITH GRANT OPTION")
                }

        @JvmStatic
        fun mysqlJdbcUrl(databaseName: String): String =
            "jdbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/$databaseName?createDatabaseIfNotExist=true"
    }
}
