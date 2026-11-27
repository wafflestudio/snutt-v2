package com.wafflestudio.snutt.migration.rehearsal

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.TimeZone
import kotlin.system.exitProcess

/**
 * 리허설 CLI. Gradle 태스크로 부른다(docs/migration-rehearsal.md).
 *
 * ```
 * ./gradlew :migration:maskDump --args="--mongo-archive=raw/snutt.archive.gz --ev-dump=raw/snutt-ev.sql.gz --out=masked"
 * ./gradlew :migration:rehearse --args="--mongo-archive=masked/mongo.archive.gz --ev-dump=masked/ev.sql.gz --out=report"
 * ```
 */
fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    // Gradle 태스크가 명령(mask, run)을 --args 뒤에 붙이므로 위치와 무관하게 찾는다.
    val command = args.singleOrNull { !it.startsWith("--") }
    val options =
        args.filter { it.startsWith("--") }.associate { arg ->
            arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=', "true")
        }

    fun path(key: String): Path =
        Paths.get(options[key] ?: usage("--$key 가 필요하다")).toAbsolutePath().also {
            if (key != "out") require(Files.isRegularFile(it)) { "--$key=$it 파일이 없다" }
        }

    val code =
        when (command) {
            "mask" -> {
                val secretEnv = options["secret-env"] ?: "REHEARSAL_MASK_SECRET"
                val secret = System.getenv(secretEnv) ?: usage("마스킹 비밀값 환경변수 $secretEnv 가 없다(16바이트 이상, 저장하지 않는다)")
                val report =
                    DumpMasking.run(
                        MaskOptions(
                            mongoArchive = path("mongo-archive"),
                            evDump = path("ev-dump"),
                            outDir = path("out"),
                            secret = secret.toByteArray(),
                            mongoDatabase = options["mongo-database"] ?: "snutt",
                            dropUnknownCollections = options["drop-unknown-collections"]?.toBooleanStrict() ?: false,
                        ),
                    )
                println(report.toMarkdown())
                println("마스킹 완료: ${path("out")}")
                0
            }
            "run" -> {
                val report =
                    Rehearsal.run(
                        RehearsalOptions(
                            mongoArchive = path("mongo-archive"),
                            evDump = path("ev-dump"),
                            outDir = path("out"),
                            mongoDatabase = options["mongo-database"] ?: "snutt",
                            sampleUsers = options["sample-users"]?.toInt() ?: 200,
                            sampleSeed = options["sample-seed"]?.toLong() ?: RehearsalOptions.DEFAULT_SEED,
                            parityUsers = options["parity-users"]?.toInt() ?: 50,
                            allowUnmasked = options["allow-unmasked"]?.toBooleanStrict() ?: false,
                        ),
                    )
                println("리허설 ${if (report.passed) "통과" else "실패"}: ${path("out").resolve("rehearsal-report.md")}")
                report.failures.forEach { println("  - $it") }
                if (report.passed) 0 else 1
            }
            else -> usage("명령은 mask 또는 run")
        }
    exitProcess(code)
}

private fun usage(message: String): Nothing {
    System.err.println(message)
    System.err.println("사용법: docs/migration-rehearsal.md")
    exitProcess(2)
}
