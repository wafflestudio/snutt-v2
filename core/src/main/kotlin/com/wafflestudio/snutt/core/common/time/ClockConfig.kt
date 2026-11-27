package com.wafflestudio.snutt.core.common.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 현재 시각은 이 Clock으로만 읽는다. 테스트는 testFixtures의 MutableClock으로 바꿔 끼운다. */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
