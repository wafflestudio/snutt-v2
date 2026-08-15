package com.wafflestudio.snutt.api

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer

abstract class AbstractMysqlIntegrationTest {
    companion object {
        @JvmStatic
        val mysql: MySQLContainer =
            MySQLContainer("mysql:8.4")
                .apply { start() }
                .apply {
                    execInContainer("mysql", "-uroot", "-ptest", "-e", "GRANT ALL PRIVILEGES ON *.* TO 'test'@'%' WITH GRANT OPTION")
                }

        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer("redis:7-alpine").withExposedPorts(6379).apply { start() }

        @JvmStatic
        fun mysqlJdbcUrl(databaseName: String): String =
            "jdbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/$databaseName?createDatabaseIfNotExist=true"

        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }
    }
}
