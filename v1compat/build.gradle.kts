plugins {
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
}

allOpen {
    annotation("jakarta.persistence.Entity")
}

noArg {
    annotation("jakarta.persistence.Entity")
}

dependencies {
    implementation(project(":core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
}
