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

// BrandColourTest reads NetworkSpec's source, so Gradle has to see it as a test input.
repositoryRootTestInputs {
    reads("proxy/src/main/java/eu/nordtal/s2/proxy/config/NetworkSpec.java")

    // NobodyComparesAgainstOneLimboTest walks this module's own sources.
    reads("proxy/src/main")

    // ComposeTellsTheStandbyApartTest reads the deployment file itself.
    reads("compose.yml")
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    // :commands carries the declarations, decisions and message keys; this module is the adapter.
    implementation(project(":commands"))

    // The admin tag and the flags in private messages are pack glyphs.
    implementation(project(":pack-rendering"))
    implementation(project(":limbo-protocol"))

    // Flyway is excluded by group: this module never migrates, and a shadowJar exclude would leave its subtree.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // AccessPool builds a HikariCP pool directly; the catalog pins jcore's own version.
    implementation(libs.hikaricp)

    // PostgresPhaseNotifications needs PGConnection, and PlaytimeStore installs jdbi3-core's PostgresPlugin.
    implementation(libs.jdbi.postgres)
    implementation(libs.postgresql.driver)

    // For Adventure's Component; no test starts a proxy.
    testImplementation(libs.velocity.api)

    // PlaytimeDao's upsert runs against a real PostgreSQL; Flyway never reaches the shaded jar.
    testImplementation(testFixtures(project(":database")))
}

messageSpec {
    specClass.set("eu.nordtal.s2.proxy.ProxyMessages")
}
