package com.wafflestudio.snutt.core.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfig {
    @Bean
    fun restClient(builder: RestClient.Builder): RestClient = builder.build()
}
