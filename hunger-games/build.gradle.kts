import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// How Steward shows this module's settings; steward-agent reads it out of the jar.
pluginDescriptor {
    displayName.set("Hunger Games")
}

// The base runs prepare() and enable() before anything reads HungerGamesPlugin's fields.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.season.hungergames.HungerGamesPlugin.prepare," +
                "eu.nordtal.season.hungergames.HungerGamesPlugin.enable," +
                "eu.nordtal.season.hungergames.HungerGamesPlugin.wireGameSystems," +
                "eu.nordtal.season.hungergames.HungerGamesPlugin.wireListeners," +
                "eu.nordtal.season.hungergames.HungerGamesPlugin.wireCommands",
        )
    }
}

// Files outside any source set that tests read; undeclared, editing one leaves :hunger-games:test UP-TO-DATE.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("hunger-games/src/main/resources/messages/hunger-games/en.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/de.properties")
}

dependencies {
    // JDBI, which :database and :settings only compile against.
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)

    // The pool :settings opens.
    implementation(libs.hikaricp)

    // HungerGamesDao installs JDBI's PostgresPlugin.
    implementation(libs.jdbi.postgres)

    // KillCountsIntegrationTest runs killCounts on the real schema: count(*) is bigint, which no fake catches.
    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))

    // A test builds a PGSimpleDataSource by hand.
    testImplementation(libs.postgresql.driver)
}

messageSpec {
    specClasses.add("eu.nordtal.season.hungergames.HungerGamesMessages")
}
