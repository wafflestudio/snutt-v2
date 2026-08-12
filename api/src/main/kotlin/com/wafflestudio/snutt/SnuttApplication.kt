package com.wafflestudio.snutt

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class SnuttApplication

fun main(args: Array<String>) {
    runApplication<SnuttApplication>(*args)
}
