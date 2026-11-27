dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.mongodb:mongodb-driver-sync")

    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-mysql")
    testImplementation("com.mysql:mysql-connector-j")
    testImplementation("org.testcontainers:testcontainers-mysql")
}

// 마이그레이션 리허설 CLI(테스트 소스의 rehearsal 패키지, Testcontainers로 로컬 Mongo·MySQL을 띄운다). docs/migration-rehearsal.md.
// 경로 인자는 레포 루트 기준이다.
//   ./gradlew :migration:maskDump --args="--mongo-archive=… --ev-dump=… --out=…"   (REHEARSAL_MASK_SECRET 환경변수 필요)
//   ./gradlew :migration:rehearse --args="--mongo-archive=… --ev-dump=… --out=…"
val testSourceSet = sourceSets["test"]
mapOf("maskDump" to "mask", "rehearse" to "run").forEach { (taskName, command) ->
    tasks.register<JavaExec>(taskName) {
        group = "migration rehearsal"
        description = "마이그레이션 리허설: $command (docs/migration-rehearsal.md)"
        classpath = testSourceSet.runtimeClasspath
        mainClass.set("com.wafflestudio.snutt.migration.rehearsal.RehearsalMainKt")
        workingDir = rootProject.projectDir
        argumentProviders.add(CommandLineArgumentProvider { listOf(command) })
        systemProperty("user.timezone", "UTC")
        providers.systemProperty("rehearsal.mysql-buffer-pool").orNull?.let { systemProperty("rehearsal.mysql-buffer-pool", it) }
        maxHeapSize = providers.systemProperty("rehearsal.heap").orElse("2g").get()
    }
}
