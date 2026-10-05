import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
    id("nordtal.plugin-descriptor")
}

// How Steward shows this module's settings; steward-agent reads it out of the jar.
pluginDescriptor {
    displayName.set("Limbo")
}

// The base runs prepare() and enable() before anything reads LimboPlugin's fields.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.season.limbo.LimboPlugin.prepare,eu.nordtal.season.limbo.LimboPlugin.enable",
        )
    }
}

dependencies {
    implementation(project(":limbo-protocol"))
    // JDBI, which :database and :settings only compile against.
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)

    // The plugin base opens its pool through :settings, which only compiles against HikariCP.
    implementation(libs.hikaricp)

    // :common's JdbiAccessDirectory installs JDBI's PostgresPlugin.
    implementation(libs.jdbi.postgres)

    // The settings, loaded in a test as the plugin loads them.
    testImplementation(testFixtures(project(":settings")))
}

messageSpec {
    specClasses.add("eu.nordtal.season.limbo.LimboMessages")
}
