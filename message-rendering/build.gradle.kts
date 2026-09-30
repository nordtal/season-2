plugins {
    id("nordtal.java-base")
    id("java-library")
}

dependencies {
    api(project(":messages"))

    // Adventure comes from the platform at runtime and is never shaded.
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)

    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.gson)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.logback.classic)
}
