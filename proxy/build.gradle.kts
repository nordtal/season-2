plugins {
    id("nordtal.velocity-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// How Steward shows this module's settings; steward-agent reads it out of the jar.
pluginDescriptor {
    displayName.set("Proxy")
}

// ComposeTellsTheStandbyApartTest reads the deployment file itself, so Gradle has to see it as a test input.
// ProxyTimersTest reads the plugin's own wiring, so Gradle has to see that as a test input too.
repositoryRootTestInputs {
    reads("compose.yml")
    reads("proxy/src/main/java/eu/nordtal/season/proxy/ProxyPlugin.java")
}

dependencies {
    // The access reader, the inbox and the signal hub.
    implementation(project(":database"))

    // The admin tag and the flags in private messages are pack glyphs.
    implementation(project(":pack-rendering"))
    implementation(project(":limbo-protocol"))

    // JDBI, which :database and :settings only compile against.
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)

    // The one database group, the colours group and pool every Minecraft process shares.
    implementation(project(":settings"))

    // :settings opens the pool and only compiles against HikariCP.
    implementation(libs.hikaricp)

    // PostgresPhaseNotifications needs PGConnection, and PlaytimeStore installs jdbi3-core's PostgresPlugin.
    implementation(libs.jdbi.postgres)
    implementation(libs.postgresql.driver)

    // For Adventure's Component; no test starts a proxy.
    testImplementation(libs.velocity.api)

    // ComposeFile, which reads the deployment.
    testImplementation(testFixtures(project(":common")))

    // PlaytimeDao's upsert runs against a real PostgreSQL; Flyway never reaches the shaded jar.
    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))
}

messageSpec {
    specClasses.add("eu.nordtal.season.proxy.ProxyMessages")
}
