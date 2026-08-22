dependencies {
    implementation(project(":core"))
    implementation(project(":v1compat"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    testImplementation("io.jsonwebtoken:jjwt-api:0.13.0")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x 아티팩트 명 (mysql/junit-jupiter → testcontainers-*)
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
