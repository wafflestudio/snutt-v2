dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.mongodb:mongodb-driver-sync")

    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-mysql")
    testImplementation("com.mysql:mysql-connector-j")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
