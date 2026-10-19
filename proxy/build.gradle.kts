plugins {
    id("nordtal.velocity-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// steward-agent reads the settings display from the jar.
pluginDescriptor {
    followsMessages.set(true)
}

// ComposeTellsTheStandbyApartTest and ProxyTimersTest read these files.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("proxy/src/main/java/eu/nordtal/season/proxy/ProxyPlugin.java")
}

dependencies {
    implementation(project(":database"))

    implementation(project(":pack-rendering"))
    implementation(project(":limbo-protocol"))

    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)

    implementation(project(":settings"))

    implementation(libs.hikaricp)

    implementation(libs.jdbi.postgres)
    implementation(libs.postgresql.driver)

    testImplementation(libs.velocity.api)

    testImplementation(testFixtures(project(":common")))

    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))
}

messageSpec {
    documentClasses.add("eu.nordtal.season.proxy.gate.GateMessages")
    documentClasses.add("eu.nordtal.season.proxy.gate.MisconfiguredGate")
    documentClasses.add("eu.nordtal.season.proxy.launch.LaunchCountdown")
    documentClasses.add("eu.nordtal.season.proxy.pack.PackMessages")
    documentClasses.add("eu.nordtal.season.proxy.ping.NetworkPing")
    documentClasses.add("eu.nordtal.season.proxy.update.RestartWatch")
    documentClasses.add("eu.nordtal.season.proxy.update.Homecoming")
    documentClasses.add("eu.nordtal.season.proxy.command.CommandGate")
    documentClasses.add("eu.nordtal.season.proxy.command.Replies")
    documentClasses.add("eu.nordtal.season.proxy.command.InfoTexts")
}
