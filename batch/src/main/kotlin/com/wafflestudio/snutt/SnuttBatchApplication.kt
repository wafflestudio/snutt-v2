package com.wafflestudio.snutt

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
class SnuttBatchApplication

fun main(args: Array<String>) {
    exitProcess(SpringApplication.exit(runApplication<SnuttBatchApplication>(*args)))
}
