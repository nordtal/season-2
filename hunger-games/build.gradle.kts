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
    documentClasses.add("eu.nordtal.season.hungergames.HungerGamesPlugin")
    documentClasses.add("eu.nordtal.season.hungergames.border.BorderController")
    documentClasses.add("eu.nordtal.season.hungergames.combat.CombatListener")
    documentClasses.add("eu.nordtal.season.hungergames.game.Ceremony")
    documentClasses.add("eu.nordtal.season.hungergames.game.HungerGamesManager")
    documentClasses.add("eu.nordtal.season.hungergames.game.WinTracker")
    documentClasses.add("eu.nordtal.season.hungergames.hud.GameHud")
    documentClasses.add("eu.nordtal.season.hungergames.lobby.Lobby")
    documentClasses.add("eu.nordtal.season.hungergames.loot.LootRefill")
    documentClasses.add("eu.nordtal.season.hungergames.presence.DisconnectBodies")
}
