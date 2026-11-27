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
    val separatedTags =
        mapOf(
            "parity" to "parityTest",
            "perf" to "perfTest",
            "stress" to "stressTest",
            "parity-known-failure" to "parityKnownFailureTest",
        )

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
            useJUnitPlatform { includeTags(tag) }
            // 아직 해당 태그 테스트가 없는 모듈에서도 태스크가 성공해야 한다.
            failOnNoDiscoveredTests = false
            shouldRunAfter(tasks.named("test"))
        }
    }
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
