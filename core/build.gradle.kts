plugins {
    `java-library`
    id("org.hibernate.orm") apply false
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
}

if (gradle.startParameter.taskNames.none { it.contains("ktlint", ignoreCase = true) }) {
    apply(plugin = "org.hibernate.orm")
    configure<org.hibernate.orm.tooling.gradle.HibernateOrmSpec> {
        enhancement { }
    }
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
    implementation("org.springframework.security:spring-security-crypto")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")

    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:junit-jupiter")
}
