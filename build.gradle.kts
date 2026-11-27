import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.allopen") version "2.3.21"
    kotlin("plugin.noarg") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "com.wafflestudio"
version = "2.0.0"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply {
        plugin("kotlin")
        plugin("org.jetbrains.kotlin.plugin.spring")
        plugin("io.spring.dependency-management")
        plugin("org.jlleitschuh.gradle.ktlint")
    }

    dependencyManagement {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.2")
            mavenBom("com.oracle.oci.sdk:oci-java-sdk-bom:3.102.0")
        }
    }

    dependencies {
        implementation("org.jetbrains.kotlin:kotlin-reflect")
        implementation("tools.jackson.module:jackson-module-kotlin")

        testImplementation("org.springframework.boot:spring-boot-starter-test")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<Test> {
        systemProperty("spring.profiles.active", "test")
        // 운영 main()이 TimeZone.setDefault(UTC)를 하므로 테스트 JVM도 로컬 타임존과 무관하게 UTC로 맞춘다.
        systemProperty("user.timezone", "UTC")
        useJUnitPlatform()
    }

    // 태그가 붙은 테스트는 PR CI(`test`)에서 빠지고 태그별 전용 태스크로만 돈다. 값은 testFixtures의 TestTags와 같다.
    // parity-known-failure는 parity가 찾은 실제 버그를 재현하는 시나리오다. 고쳐지기 전까지 빨간 상태라 parityTest와 분리한다.
    // parity-snapshot은 시드 대신 운영 덤프를 넣어 도는 parity(작업 M 리허설용)다. 덤프 경로를 줘야 돈다.
    // live는 실제 외부 사이트(sugang.snu.ac.kr)를 호출하므로 사람이 직접 `./gradlew liveTest`로만 돌린다.
    // stress-known-failure는 stress가 재현한 버그를 고정한 테스트다. 고칠 때까지 test와 stressTest에서 빠진다.
    val separatedTags =
        mapOf(
            "parity" to "parityTest",
            "perf" to "perfTest",
            "stress" to "stressTest",
            "live" to "liveTest",
            "parity-known-failure" to "parityKnownFailureTest",
            "parity-snapshot" to "paritySnapshotTest",
            "stress-known-failure" to "stressKnownFailureTest",
        )
    // 다른 태그와 함께 붙을 수 있는 보조 태그. 자기 전용 태스크가 아니면 항상 뺀다.
    val auxiliaryTags = setOf("parity-known-failure", "parity-snapshot", "stress-known-failure")

    tasks.named<Test>("test") {
        useJUnitPlatform { excludeTags(*separatedTags.keys.toTypedArray()) }
    }

    val testSourceSet = the<SourceSetContainer>()["test"]
    separatedTags.forEach { (tag, taskName) ->
        tasks.register<Test>(taskName) {
            description = "JUnit 태그 '$tag'가 붙은 테스트만 실행한다."
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform {
                includeTags(tag)
                excludeTags(*(auxiliaryTags - tag).toTypedArray())
            }
            // 아직 해당 태그 테스트가 없는 모듈에서도 태스크가 성공해야 한다.
            failOnNoDiscoveredTests = false
            shouldRunAfter(tasks.named("test"))
            if (tag == "stress") configureStress(this, "stress")
            if (tag == "stress-known-failure") configureStress(this, "stress-known-failure")
            if (tag == "live") {
                // 외부 상태를 확인하는 테스트라 매번 실제로 돌아야 한다.
                outputs.upToDateWhen { false }
                // -PrefreshFixtures를 주면 받은 응답으로 src/test/resources/fixtures를 갱신한다.
                systemProperty("snutt.live.refreshFixtures", project.hasProperty("refreshFixtures").toString())
                systemProperty("snutt.live.fixturesDir", project.file("src/test/resources/fixtures").absolutePath)
            }
        }
    }
}

/**
 * stress 규모는 Gradle 프로퍼티로 바꾼다: -PstressThreads, -PstressIterations, -PstressOpsPerThread, -PstressSeed.
 * 값을 주지 않으면 테스트 코드의 기본값(StressSettings)을 쓴다. 리포트는 build/reports/stress(known-failure는 stress-known-failure)에 남는다.
 */
fun Project.configureStress(
    task: Test,
    reportName: String,
) {
    val reportDir =
        layout.buildDirectory
            .dir("reports/$reportName")
            .get()
            .asFile
    mapOf(
        "stressThreads" to "snutt.stress.threads",
        "stressIterations" to "snutt.stress.iterations",
        "stressOpsPerThread" to "snutt.stress.opsPerThread",
        "stressSeed" to "snutt.stress.seed",
    ).forEach { (property, systemProperty) ->
        findProperty(property)?.let { task.systemProperty(systemProperty, it.toString()) }
    }
    task.systemProperty("snutt.stress.reportDir", reportDir.absolutePath)
    // 결과는 매번의 인터리빙에 달려 있으므로 캐시된 결과를 쓰지 않는다.
    task.outputs.upToDateWhen { false }
    task.doFirst { reportDir.deleteRecursively() }
    task.testLogging { showStandardStreams = false }
}

project(":api") {
    apply(plugin = "org.springframework.boot")

    val bootJar = tasks.getByName<BootJar>("bootJar")
    bootJar.archiveFileName.set("snutt-api.jar")
}

project(":batch") {
    apply(plugin = "org.springframework.boot")

    val bootJar = tasks.getByName<BootJar>("bootJar")
    bootJar.archiveFileName.set("snutt-batch.jar")
}

project(":migration") {
    apply(plugin = "org.springframework.boot")

    val bootJar = tasks.getByName<BootJar>("bootJar")
    bootJar.archiveFileName.set("snutt-migration.jar")
}
