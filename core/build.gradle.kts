plugins {
    `java-library`
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
    kotlin("kapt")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
}

noArg {
    annotation("jakarta.persistence.Entity")
}

dependencies {
    // ErrorType이 HttpStatus를, 소셜 클라이언트가 RestClient를 참조 (서블릿 스택 없이 spring-web 모듈만)
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
    implementation("io.github.openfeign.querydsl:querydsl-jpa:7.5")
    implementation("com.google.firebase:firebase-admin:9.10.0")
    // 팝업 이미지 업로드용 사전 인증 요청(PAR) 발급. 요청 서명이 필요해 SDK를 쓴다
    implementation("com.oracle.oci.sdk:oci-java-sdk-objectstorage:3.94.1")
    implementation("com.oracle.oci.sdk:oci-java-sdk-common-httpclient-jersey3:3.94.1")
    kapt("io.github.openfeign.querydsl:querydsl-apt:7.5:jakarta")
    // Hibernate 7.4 JSON 컬럼 매핑(Jackson 3 FormatMapper)과 @JsonValue/@JsonCreator 사용
    implementation("tools.jackson.core:jackson-databind")

    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x 아티팩트 명 (mysql/junit-jupiter → testcontainers-*)
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
