package com.wafflestudio.snutt.api.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** 통합테스트는 `snutt.scheduling.enabled=false`로 끄고 스케줄러 메서드를 직접 호출한다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["snutt.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfig
