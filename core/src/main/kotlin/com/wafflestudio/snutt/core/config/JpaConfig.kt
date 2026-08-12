package com.wafflestudio.snutt.core.config

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

@Configuration
class JpaConfig {
    @Bean
    fun jpaQueryFactory(entityManager: EntityManager): JPAQueryFactory = JPAQueryFactory(entityManager)

    @Bean
    fun githubRestClient(): RestClient = RestClient.builder().baseUrl("https://api.github.com").build()

    // core를 쓰는 모든 모듈(batch/migration)에 Jackson 빈을 제공한다 (api는 Boot 자동 설정)
    @Bean
    fun objectMapper(): ObjectMapper = JsonMapper.builder().findAndAddModules().build()
}
