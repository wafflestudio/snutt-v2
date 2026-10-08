package com.wafflestudio.snutt.core.test

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer

@TestConfiguration(proxyBeanMethods = false)
class IntegrationContainers {
    companion object {
        val mysql: MySQLContainer = MySQLContainer("mysql:26.7").apply { start() }

        val redis: GenericContainer<*> =
            GenericContainer("valkey/valkey:9-alpine").withExposedPorts(6379).apply { start() }
    }

    @Bean
    fun containerProperties(): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }
}
