plugins {
    id("info.solidsoft.pitest") version "1.19.0"
}

dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))

    implementation("org.apache.poi:poi-ooxml:6.0.0")
    implementation("org.jsoup:jsoup:1.24.1")
}

// mutation testing(`./gradlew :batch:pitest`, 주 1회·수동). 수강편람 동기화(sugangsnu)만 대상으로 한다. docs/testing.md "mutation testing".
pitest {
    pitestVersion.set("1.30.2")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("com.wafflestudio.snutt.batch.sugangsnu.*"))
    // 기본값은 targetClasses와 같은 패키지라 batch/sugang 등의 테스트를 못 찾는다.
    targetTests.set(listOf("com.wafflestudio.snutt.batch.*"))
    // 태그가 붙은 테스트(live는 실제 사이트를 부른다, stress는 느리다)는 PR CI와 같이 뺀다.
    excludedGroups.set(
        listOf("parity", "perf", "stress", "live", "parity-known-failure", "parity-snapshot", "rehearsal", "stress-known-failure"),
    )
    // Kotlin이 만드는 null 검사(Intrinsics)와 data class 메서드는 죽일 수 없는 mutant만 만든다.
    avoidCallsTo.set(listOf("kotlin.jvm.internal", "org.slf4j"))
    excludedMethods.set(listOf("equals", "hashCode", "toString", "component*", "copy", "copy\$default"))
    threads.set(providers.gradleProperty("pitestThreads").map(String::toInt).orElse(2))
    // minion JVM은 Test 태스크 설정을 물려받지 않는다. 루트 build.gradle.kts의 Test 공통 설정과 맞춘다.
    jvmArgs.set(listOf("-Dspring.profiles.active=test", "-Duser.timezone=UTC", "-Xmx1g"))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    exportLineCoverage.set(true)
}
