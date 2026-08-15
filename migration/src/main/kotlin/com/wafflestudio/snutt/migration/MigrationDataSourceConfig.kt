package com.wafflestudio.snutt.migration

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Configuration
class MigrationDataSourceConfig {
    @Bean
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
    fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

    @Bean
    fun mongoClient(
        @Value("\${migration.mongo.uri}") uri: String,
    ): MongoClient = MongoClients.create(uri)
}

@Component
class MongoSource(
    private val mongoClient: MongoClient,
    @param:Value("\${migration.mongo.database:snutt}") private val database: String,
) {
    fun collection(name: String): MongoCollection<Document> = db().getCollection(name)

    fun count(name: String): Long = collection(name).countDocuments()

    fun each(
        name: String,
        block: (Document) -> Unit,
    ) {
        collection(name).find().batchSize(BATCH_SIZE).forEach(block)
    }

    private fun db(): MongoDatabase = mongoClient.getDatabase(database)

    companion object {
        private const val BATCH_SIZE = 2_000
    }
}

@Component
class EvSource(
    @param:Value("\${migration.old-ev.url:}") private val url: String,
    @param:Value("\${migration.old-ev.username:}") private val username: String,
    @param:Value("\${migration.old-ev.password:}") private val password: String,
) {
    val available: Boolean = url.isNotBlank()

    val jdbc: JdbcTemplate by lazy {
        check(available) { "구 ev DB 접속 정보(migration.old-ev.url)가 없다" }
        JdbcTemplate(
            DataSourceBuilder
                .create()
                .url(url)
                .username(username)
                .password(password)
                .build(),
        )
    }
}
