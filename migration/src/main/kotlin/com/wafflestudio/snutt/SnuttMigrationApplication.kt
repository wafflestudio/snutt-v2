package com.wafflestudio.snutt

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.util.TimeZone
import kotlin.system.exitProcess

@SpringBootApplication
class SnuttMigrationApplication

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    exitProcess(SpringApplication.exit(runApplication<SnuttMigrationApplication>(*args)))
}
