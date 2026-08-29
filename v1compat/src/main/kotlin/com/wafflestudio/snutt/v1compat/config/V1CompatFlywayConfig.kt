package com.wafflestudio.snutt.v1compat.config

import org.flywaydb.core.Flyway
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

class V1CompatSchemaInitializer(
    private val dataSource: DataSource,
) : InitializingBean {
    override fun afterPropertiesSet() {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations(LOCATION)
            .table(HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()
    }

    companion object {
        const val LOCATION = "classpath:db/v1compat"
        const val HISTORY_TABLE = "flyway_schema_history_v1compat"
    }
}

@Configuration
class V1CompatFlywayConfig {
    @Bean
    fun v1CompatSchemaInitializer(
        dataSource: DataSource,
        coreSchema: FlywayMigrationInitializer,
    ): V1CompatSchemaInitializer = V1CompatSchemaInitializer(dataSource)

    @Bean
    fun v1CompatEntityManagerFactoryDependsOnPostProcessor(): EntityManagerFactoryDependsOnPostProcessor =
        EntityManagerFactoryDependsOnPostProcessor("v1CompatSchemaInitializer")
}
