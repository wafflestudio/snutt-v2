package com.wafflestudio.snutt.migration

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

// 일회성 big-bang 이관 (PLAN.md §5): Mongo + 구 ev MySQL 읽기 → 신 MySQL 쓰기
@Configuration
class MigrationDataSourceConfig {
    @Bean
    @Primary
    fun newMysqlDataSource(
        @Value("\${migration.target.url}") url: String,
        @Value("\${migration.target.username}") username: String,
        @Value("\${migration.target.password}") password: String,
    ): DataSource =
        DataSourceBuilder
            .create()
            .url(url)
            .username(username)
            .password(password)
            .build()

    @Bean
    fun oldEvDataSource(
        @Value("\${migration.old-ev.url}") url: String,
        @Value("\${migration.old-ev.username}") username: String,
        @Value("\${migration.old-ev.password}") password: String,
    ): DataSource =
        DataSourceBuilder
            .create()
            .url(url)
            .username(username)
            .password(password)
            .build()

    @Bean
    fun mongoClient(
        @Value("\${migration.mongo.uri}") uri: String,
    ): MongoClient = MongoClients.create(uri)

    @Bean
    fun newMysqlJdbcTemplate(
        @Qualifier("newMysqlDataSource") dataSource: DataSource,
    ): JdbcTemplate = JdbcTemplate(dataSource)

    @Bean
    fun oldEvJdbcTemplate(
        @Qualifier("oldEvDataSource") dataSource: DataSource,
    ): JdbcTemplate = JdbcTemplate(dataSource)
}
