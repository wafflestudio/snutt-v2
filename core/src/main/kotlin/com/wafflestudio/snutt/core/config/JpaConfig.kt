package com.wafflestudio.snutt.core.config

import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

@Configuration
class JpaConfig {
    @Bean
    fun jpqlRenderContext(): JpqlRenderContext = JpqlRenderContext()

    @Bean
    fun githubRestClient(): RestClient = RestClient.builder().baseUrl("https://api.github.com").build()

    @Bean
    fun objectMapper(): ObjectMapper = JsonMapper.builder().findAndAddModules().build()
}
