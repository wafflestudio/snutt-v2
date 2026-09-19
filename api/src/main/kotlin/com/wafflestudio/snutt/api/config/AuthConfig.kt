package com.wafflestudio.snutt.api.config

import com.wafflestudio.snutt.core.common.client.PlatformKeys
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AuthConfig {
    @Bean
    fun platformKeys(
        @Value("\${snutt.auth.platform-keys}") config: String,
    ): PlatformKeys = PlatformKeys(config)
}
