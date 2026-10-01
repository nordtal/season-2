plugins {
    id("nordtal.java-base")
    id("java-library")
    // An in-memory store, for every module whose tests load its settings.
    id("java-test-fixtures")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The colours map onto the message system's tones.
    api(project(":messages"))
    // The settings live in the database, and a change arrives on the signal hub.
    api(project(":database"))

    // compileOnly: every consumer already carries jcore, Gson and HikariCP, shaded or not, in the version it needs.
    compileOnly(libs.jcore)
    compileOnly(libs.gson)
    compileOnly(libs.hikaricp)
    compileOnly(libs.slf4j.api)

    testFixturesCompileOnly(libs.jspecify)
    testFixturesImplementation(libs.jcore)
    testFixturesImplementation(libs.gson)
    testFixturesImplementation(libs.slf4j.api)

    testImplementation(libs.jcore)
    testImplementation(libs.gson)
    testImplementation(libs.slf4j.api)
    testImplementation(testFixtures(project(":database")))
    testImplementation(libs.bundles.access.persistence)
    testImplementation(libs.postgresql.driver)
    testRuntimeOnly(libs.logback.classic)
}
