package com.wafflestudio.snutt.api

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer

/**
 * 테스트 클래스마다 전용 데이터베이스를 사용해 데이터를 격리한다.
 * 서버(컨테이너)는 MySQL·Redis 모두 JVM당 1회만 기동하고, 하위 클래스는 companion object의
 * [DynamicPropertySource]로 자신만의 데이터베이스 이름을 등록한다.
 *
 * test 유저는 기본적으로 test.* 만 접근 가능하므로, 기동 시 전체 DB에 대한
 * 권한을 부여한다 (createDatabaseIfNotExist로 클래스별 DB가 생성된다).
 */
abstract class AbstractMysqlIntegrationTest {
    companion object {
        @JvmStatic
        val mysql: MySQLContainer<*> =
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
