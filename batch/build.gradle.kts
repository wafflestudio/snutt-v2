dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))

    implementation("org.apache.poi:poi-ooxml:6.0.0")
    implementation("org.jsoup:jsoup:1.24.1")
}
