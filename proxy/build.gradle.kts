import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.velocity-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// How Steward shows this module's settings; steward-agent reads it out of the jar.
pluginDescriptor {
    displayName.set("Proxy")
}

// Names start() as NullAway's initializer, since ProxyPlugin's fields are set there.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.season.proxy.ProxyPlugin.start",
        )
    }
}

// ComposeTellsTheStandbyApartTest reads the deployment file itself, so Gradle has to see it as a test input.
repositoryRootTestInputs {
    reads("compose.yml")
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

    // PlaytimeDao's upsert runs against a real PostgreSQL; Flyway never reaches the shaded jar.
    testImplementation(testFixtures(project(":database")))
    testImplementation(testFixtures(project(":settings")))
}

messageSpec {
    specClasses.add("eu.nordtal.season.proxy.ProxyMessages")
}
