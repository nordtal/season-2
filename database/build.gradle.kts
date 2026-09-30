plugins {
    id("nordtal.java-base")
    id("java-library")
    // AccessSchema, for every module whose tests need the migrated schema.
    id("java-test-fixtures")
}

// Other modules' sources that the wiring tests here read as text.
repositoryRootTestInputs {
    reads("smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java")
    reads("limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java")
    reads("proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java")
    readsTree("smp/src/main/java")
    readsTree("limbo/src/main/java")
    readsTree("hunger-games/src/main/java")
    readsTree("paper-common/src/main/java")
    readsTree("proxy/src/main/java")
}

dependencies {
    api(project(":common"))

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

    testImplementation(testFixtures(project(":common")))
    // AccessProfileIntegrationTest reads a player's language through PlayerLocales, as the plugins do.
    testImplementation(project(":messages"))
    testImplementation(libs.bundles.access.persistence)
    testImplementation(libs.testcontainers.postgresql)
    // Compile scope: the integration tests build a PGSimpleDataSource by hand.
    testImplementation(libs.postgresql.driver)
    testRuntimeOnly(libs.logback.classic)
}
