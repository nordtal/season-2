import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.velocity-plugin")
    id("nordtal.message-spec")
}

// Names start() as NullAway's initializer, since ProxyPlugin's fields are set there.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.s2.proxy.ProxyPlugin.start",
        )
    }
}

// ComposeTellsTheStandbyApartTest reads the deployment file itself, so Gradle has to see it as a test input.
repositoryRootTestInputs {
    reads("compose.yml")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // The access reader, the inbox and the signal hub.
    implementation(project(":database"))

    // The admin tag and the flags in private messages are pack glyphs.
    implementation(project(":pack-rendering"))
    implementation(project(":limbo-protocol"))

    // Flyway is excluded by group: this module never migrates, and a shadowJar exclude would leave its subtree.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // The one database.yml, colours.yml and pool every Minecraft process shares.
    implementation(project(":settings"))

    // :settings opens the pool and only compiles against HikariCP; the catalog pins jcore's own version.
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
    specClass.set("eu.nordtal.s2.proxy.ProxyMessages")
}
