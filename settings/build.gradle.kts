plugins {
    id("nordtal.java-base")
    id("java-library")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The colours map onto the message system's tones.
    api(project(":messages"))

    // compileOnly: every consumer already carries jcore and HikariCP, shaded or not, in the version it needs.
    compileOnly(libs.jcore)
    compileOnly(libs.hikaricp)
    compileOnly(libs.slf4j.api)

    testImplementation(libs.jcore)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.logback.classic)
}
