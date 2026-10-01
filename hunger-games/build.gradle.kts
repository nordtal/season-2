import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
}

// The base runs prepare() and enable() before anything reads HungerGamesPlugin's fields.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.s2.hungergames.HungerGamesPlugin.prepare," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.enable," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.wireGameSystems," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.wireListeners," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.wireCommands",
        )
    }
}

// Files outside any source set that tests read; undeclared, editing one leaves :hunger-games:test UP-TO-DATE.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("hunger-games/src/main/resources/messages/hunger-games/en.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/de.properties")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // jcore brings the config system and the database stack; Flyway is excluded, since this plugin never migrates.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // Redeclared, since jcore declares HikariCP `implementation`; the pool is built here to tune its timeouts.
    implementation(libs.hikaricp)

    // HungerGamesDao installs JDBI's PostgresPlugin, which jcore only declares at runtime scope.
    implementation(libs.jdbi.postgres)

    // KillCountsIntegrationTest runs killCounts on the real schema: count(*) is bigint, which no fake catches.
    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))

    // jcore puts the driver on the runtime classpath only; a test builds a PGSimpleDataSource by hand.
    testImplementation(libs.postgresql.driver)
}

messageSpec {
    specClass.set("eu.nordtal.s2.hungergames.HungerGamesMessages")
}
