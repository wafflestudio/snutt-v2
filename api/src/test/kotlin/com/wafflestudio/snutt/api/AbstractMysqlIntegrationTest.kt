package com.wafflestudio.snutt.api

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer

// JVM당 1회 기동해 모든 통합 테스트가 공유하는 MySQL (Testcontainers singleton 패턴).
// @Container 어노테이션을 쓰지 않아 테스트 클래스 간 재시작이 없다
abstract class AbstractMysqlIntegrationTest {
    companion object {
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }
    }
}
