package com.wafflestudio.snutt.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class SnuttApplicationTest : AbstractMysqlIntegrationTest() {
    @Test
    fun contextLoadsAndSchemaApplies() {
        // Flyway V1이 실제 MySQL에 적용되고 컨텍스트가 부팅되면 성공
    }
}
