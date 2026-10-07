dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))

    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("org.jsoup:jsoup:1.23.1")
}
