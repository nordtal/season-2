plugins {
    id("nordtal.java-base")
    id("nordtal.message-spec")
    id("java-library")
    // TestDatabase, for every module whose tests need the migrated schema.
    id("java-test-fixtures")
}

// The test image's tag.
repositoryRootTestInputs {
    reads("compose.yml")
}

dependencies {
    api(project(":common"))
    // The refusals a write answers with are messages of this module's own bundle.
    api(project(":messages"))

    // The kernel's JSON codec runs on Gson, which every consumer has at runtime and none shades.
    compileOnly(libs.gson)
    testImplementation(libs.gson)

    // NullAway's annotations reference checker-qual at class-file level; this is the version NullAway pulls in.
    compileOnly("org.checkerframework:checker-qual:4.2.3")
    testCompileOnly("org.checkerframework:checker-qual:4.2.3")

    // compileOnly, so no consumer shades the database stack; one that needs it adds libs.bundles.access.persistence.
    compileOnly(libs.jdbi.core)
    compileOnly(libs.jdbi.sqlobject)
    compileOnly(libs.jdbi.postgres)
    compileOnly(libs.hikaricp)
    compileOnly(libs.slf4j.api)

    // The notify package unwraps PGConnection, because pgjdbc has no callback API for LISTEN.
    compileOnly(libs.postgresql.driver)

    // Flyway only in the fixture: this module never migrates, and Flyway must never reach a plugin jar.
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation(libs.flyway.postgresql)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.postgresql.driver)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")

    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.bundles.access.persistence)
    // Compile scope: the integration tests build a PGSimpleDataSource by hand.
    testImplementation(libs.postgresql.driver)
    testRuntimeOnly(libs.logback.classic)
}

messageSpec {
    specClasses.add("eu.nordtal.s2.database.DatabaseMessages")
}
