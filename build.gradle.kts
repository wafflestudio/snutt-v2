import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("org.springframework.boot") version "4.1.0" apply false
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
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
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
        useJUnitPlatform()
    }
}

project(":api") {
    apply(plugin = "org.springframework.boot")

    val bootJar: BootJar by tasks
    bootJar.archiveFileName.set("snutt-api.jar")
}

project(":batch") {
    apply(plugin = "org.springframework.boot")

    val bootJar: BootJar by tasks
    bootJar.archiveFileName.set("snutt-batch.jar")
}

project(":migration") {
    apply(plugin = "org.springframework.boot")

    val bootJar: BootJar by tasks
    bootJar.archiveFileName.set("snutt-migration.jar")
}
