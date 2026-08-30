plugins {
    `java-library`
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
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")
    api("com.linecorp.kotlin-jdsl:jpql-dsl:3.9.0")
    api("com.linecorp.kotlin-jdsl:jpql-render:3.9.0")
    api("com.linecorp.kotlin-jdsl:spring-data-jpa-boot4-support:3.9.0")
    implementation("com.google.firebase:firebase-admin:9.10.0")
    implementation("com.oracle.oci.sdk:oci-java-sdk-objectstorage:3.94.1")
    implementation("com.oracle.oci.sdk:oci-java-sdk-common-httpclient-jersey3:3.94.1")
    implementation("tools.jackson.core:jackson-databind")

    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
