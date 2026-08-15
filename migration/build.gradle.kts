dependencies {
    implementation(project(":core"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.mongodb:mongodb-driver-sync")

    testImplementation(project(":v1compat"))
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-mysql")
    testImplementation("com.mysql:mysql-connector-j")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
