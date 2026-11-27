plugins {
    `java-library`
    `java-test-fixtures`
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
}

noArg {
    annotation("jakarta.persistence.Entity")
}

dependencies {
    api("org.springframework:spring-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")
    api("com.linecorp.kotlin-jdsl:jpql-dsl:3.9.0")
    api("com.linecorp.kotlin-jdsl:jpql-render:3.9.0")
    api("com.linecorp.kotlin-jdsl:spring-data-jpa-boot4-support:3.9.0")
    implementation("com.google.firebase:firebase-admin:9.12.0")
    implementation("com.oracle.oci.sdk:oci-java-sdk-objectstorage")
    implementation("com.oracle.oci.sdk:oci-java-sdk-emaildataplane")
    implementation("com.oracle.oci.sdk:oci-java-sdk-common-httpclient-jersey3")
    implementation("tools.jackson.core:jackson-databind")

    runtimeOnly("com.mysql:mysql-connector-j")

    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.springframework.boot:spring-boot-starter-data-redis")
    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:testcontainers-mysql")
    testFixturesApi("org.mockito.kotlin:mockito-kotlin:6.4.0")
    testFixturesImplementation("net.ttddyy:datasource-proxy:1.11.0")
    // perf 데이터셋을 별도 스키마(snutt_perf)에 만들 때 운영과 같은 migration을 적용한다.
    testFixturesImplementation("org.flywaydb:flyway-mysql")
    // 외부 HTTP API를 네트워크 경계에서 mock한다. standalone은 Jetty·Jackson 2를 shading해 Spring Boot 4 의존성과 충돌하지 않는다.
    testFixturesApi("org.wiremock:wiremock-standalone:3.13.2")
}
