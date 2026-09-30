import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
}

// Names start() and its field-assigning helpers as NullAway's initializers.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.s2.hungergames.HungerGamesPlugin.start," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.loadConfigAndMessages," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.wireGameSystems," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.wireAdminWatchAndCommands," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.wireCommandFilterAndAdminWatch," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.refreshCurrentGame," +
                "eu.nordtal.s2.hungergames.HungerGamesPlugin.startHeartbeat",
        )
    }
}

// Files outside any source set that tests read; undeclared, editing one leaves :hunger-games:test UP-TO-DATE.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/command/HungerGamesCommand.java")
    reads("hunger-games/src/main/java/eu/nordtal/s2/hungergames/listener/CombatListener.java")
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

    // KillCountsIntegrationTest runs killCounts on the real migrations: count(*) is bigint, which no fake catches.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers.postgresql)

    // jcore puts the driver on the runtime classpath only; a test builds a PGSimpleDataSource by hand.
    testImplementation(libs.postgresql.driver)
}

messageSpec {
    specClass.set("eu.nordtal.s2.hungergames.HungerGamesMessages")
}
