import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// steward-agent reads the settings display from the jar.
pluginDescriptor {
    followsMessages.set(true)
}

// The plugin base class calls prepare() and enable() before any other code reads the fields.
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

// The tests read these files.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("hunger-games/src/main/resources/messages/hunger-games/en.properties")
    reads("hunger-games/src/main/resources/messages/hunger-games/de.properties")
}

dependencies {
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)

    implementation(libs.hikaricp)

    implementation(libs.jdbi.postgres)

    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":common")))
    testImplementation(testFixtures(project(":settings")))

    testImplementation(libs.postgresql.driver)
}

messageSpec {
    specClasses.add("eu.nordtal.season.hungergames.HungerGamesMessages")
    documentClasses.add("eu.nordtal.season.hungergames.HungerGamesPlugin")
    documentClasses.add("eu.nordtal.season.hungergames.presence.DisconnectBodies")
}
