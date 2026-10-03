dependencies {
    implementation(project(":core"))

    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("org.jsoup:jsoup:1.23.1")

    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
}
