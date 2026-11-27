plugins {
    id("info.solidsoft.pitest") version "1.19.0"
}

dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    implementation(project(":v1compat"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    testImplementation("io.jsonwebtoken:jjwt-api:0.13.0")
}

// mutation testing(`./gradlew :api:pitest`, 주 1회·수동). docs/testing.md "mutation testing".
// 시간표·강의평 도메인 서비스는 core에 있지만 그 동작을 검증하는 통합테스트는 api에 있으므로 api 테스트로 core 클래스를 mutate한다.
evaluationDependsOn(":core")

pitest {
    pitestVersion.set("1.30.2")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(
        listOf(
            "com.wafflestudio.snutt.core.domain.timetable.service.*",
            "com.wafflestudio.snutt.core.domain.evaluation.service.*",
        ),
    )
    // 기본값은 targetClasses와 같은 패키지라 api 테스트를 못 찾는다.
    targetTests.set(listOf("com.wafflestudio.snutt.api.*"))
    // core main을 mutate 대상 코드·리포트 소스에 넣는다.
    mainSourceSets.set(listOf(sourceSets["main"], project(":core").the<SourceSetContainer>()["main"]))
    // 태그가 붙은 테스트(perf·stress는 느리고 PR CI에서도 빠진다)는 뺀다.
    excludedGroups.set(
        listOf("parity", "perf", "stress", "live", "parity-known-failure", "parity-snapshot", "rehearsal", "stress-known-failure"),
    )
    avoidCallsTo.set(listOf("kotlin.jvm.internal", "org.slf4j"))
    excludedMethods.set(listOf("equals", "hashCode", "toString", "component*", "copy", "copy\$default"))
    threads.set(providers.gradleProperty("pitestThreads").map(String::toInt).orElse(2))
    // minion JVM은 Test 태스크 설정을 물려받지 않는다. 루트 build.gradle.kts의 Test 공통 설정과 맞춘다.
    jvmArgs.set(listOf("-Dspring.profiles.active=test", "-Duser.timezone=UTC", "-Xmx1g"))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    exportLineCoverage.set(true)
}
