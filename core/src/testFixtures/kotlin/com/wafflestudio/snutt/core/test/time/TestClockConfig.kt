package com.wafflestudio.snutt.core.test.time

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** 운영 `Clock` 빈(Clock.systemUTC())보다 우선하는 [MutableClock]을 등록한다. AbstractIntegrationTest가 import한다. */
@TestConfiguration(proxyBeanMethods = false)
class TestClockConfig {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock()
}
