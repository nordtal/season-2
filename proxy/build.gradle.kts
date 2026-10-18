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
    specClasses.add("eu.nordtal.season.proxy.ProxyMessages")
}
