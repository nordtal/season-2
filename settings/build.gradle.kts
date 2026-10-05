plugins {
    id("nordtal.java-base")
    id("java-library")
    // An in-memory store, for every module whose tests load its settings.
    id("java-test-fixtures")
}

repositoryRootTestInputs {
    reads("compose.yml")
}

dependencies {
    // The colours map onto the message system's tones.
    api(project(":messages"))
    // The settings live in the database, and a change arrives on the signal hub.
    api(project(":database"))

    // A group is a spec interface.
    api(project(":spec"))

    // compileOnly: every consumer already carries Gson, HikariCP and JDBI, shaded or not, in the version it needs.
    compileOnly(libs.gson)
    compileOnly(libs.hikaricp)
    compileOnly(libs.jdbi.core)
    compileOnly(libs.slf4j.api)

    testFixturesCompileOnly(libs.jspecify)
    testFixturesImplementation(libs.gson)
    testFixturesImplementation(libs.slf4j.api)
    testFixturesImplementation(libs.bundles.access.persistence)

    testImplementation(libs.gson)
    testImplementation(libs.slf4j.api)
    testImplementation(testFixtures(project(":common")))
    testImplementation(testFixtures(project(":database")))
    testImplementation(libs.bundles.access.persistence)
    testImplementation(libs.postgresql.driver)
    testRuntimeOnly(libs.logback.classic)
}
