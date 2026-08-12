package com.wafflestudio.snutt.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
class SnuttApplicationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")
    }

    @Test
    fun contextLoadsAndSchemaApplies() {
        // Flyway V1이 실제 MySQL에 적용되고 컨텍스트가 부팅되면 성공
    }
}
