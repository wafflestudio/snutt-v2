import java.util.Properties

// v1 서버(snutt, snutt-ev)와 v2 v1compat 엔드포인트의 응답을 비교하는 parity 하네스. 구조와 실행법은 docs/parity.md.
// 테스트 소스만 있는 모듈이다. 비교 시나리오는 `./gradlew parityTest`, 알려진 버그 재현은 `./gradlew parityKnownFailureTest`로 돈다.

val v1shim: SourceSet by sourceSets.creating

// migration 모듈은 클래스만 쓴다. jar째로 넣으면 migration의 application.yml(web-application-type: none)과
// SnuttMigrationApplication이 v2 api 테스트 컨텍스트에 섞인다.
evaluationDependsOn(":migration")
val migrationMain = project(":migration").the<SourceSetContainer>()["main"]

dependencies {
    testImplementation(project(":api"))
    testImplementation(project(":v1compat"))
    testImplementation(files(migrationMain.output.classesDirs).builtBy(":migration:classes"))
    testImplementation(project(":core"))
    testImplementation(testFixtures(project(":core")))

    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.mongodb:mongodb-driver-sync")
    testImplementation("org.springframework.security:spring-security-crypto")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("com.mysql:mysql-connector-j")
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("io.jsonwebtoken:jjwt-api:0.13.0")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    // v1 컨테이너 classpath에 얹는 shim. v1과 같은 Spring Framework 7 API만 쓴다.
    "v1shimCompileOnly"("org.springframework:spring-context")
}

val v1ShimJar =
    tasks.register<Jar>("v1ShimJar") {
        description = "v1 서버 컨테이너에 얹는 SDK 경계 stub(V1ShimInitializer) jar를 만든다."
        from(v1shim.output)
        archiveFileName.set("parity-v1-shim.jar")
        destinationDirectory.set(layout.buildDirectory.dir("v1"))
    }

val v1Versions = Properties().apply { file("v1/versions.properties").inputStream().use(::load) }

// v1 jar는 `clean`과 무관하게 SHA별로 Gradle 사용자 캐시에 둔다. 빌드에 수 분이 걸리고 SHA가 같으면 결과가 같다.
val v1CacheDir = gradle.gradleUserHomeDir.resolve("caches/snutt-parity")

fun registerV1Jar(
    key: String,
    repoEnv: String,
): Pair<TaskProvider<Exec>, File> {
    val sha = v1Versions.getProperty("$key.sha")
    val out = v1CacheDir.resolve("$key-api-$sha.jar")
    val task =
        tasks.register<Exec>("buildV1${key.replaceFirstChar(Char::uppercase)}Jar") {
            description = "v1 $key 서버 bootJar를 ${sha.take(7)}에서 빌드한다(캐시에 있으면 건너뜀)."
            val repo = providers.environmentVariable(repoEnv).orElse(v1Versions.getProperty("$key.repo")).get()
            onlyIf { !out.exists() }
            outputs.file(out)
            commandLine(
                "bash",
                file("v1/build-v1-jar.sh").absolutePath,
                repo,
                sha,
                v1Versions.getProperty("$key.task"),
                v1Versions.getProperty("$key.jar"),
                out.absolutePath,
            )
        }
    return task to out
}

val (buildSnuttJar, snuttJar) = registerV1Jar("snutt", "PARITY_SNUTT_REPO")
val (buildEvJar, evJar) = registerV1Jar("ev", "PARITY_EV_REPO")

listOf("parityTest", "parityKnownFailureTest").forEach { name ->
    tasks.named<Test>(name) {
        dependsOn(buildSnuttJar, buildEvJar, v1ShimJar)
        systemProperty("parity.v1.snutt-jar", snuttJar.absolutePath)
        systemProperty("parity.v1.ev-jar", evJar.absolutePath)
        systemProperty(
            "parity.v1.shim-jar",
            v1ShimJar
                .get()
                .archiveFile
                .get()
                .asFile.absolutePath,
        )
        systemProperty(
            "parity.report-dir",
            layout.buildDirectory
                .dir("reports/parity/$name")
                .get()
                .asFile.absolutePath,
        )
        // 운영 스냅샷으로 돌릴 때(작업 M) 넘기는 값. docs/parity.md "외부 데이터로 돌리기".
        listOf("parity.source", "parity.source.mongo-uri", "parity.source.mongo-database", "parity.source.ev-dump").forEach { key ->
            System.getProperty(key)?.let { systemProperty(key, it) }
        }
        maxHeapSize = "1g"
    }
}
