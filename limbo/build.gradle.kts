import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
}

repositories {
    maven("https://jitpack.io")
}

// The base runs prepare() and enable() before anything reads LimboPlugin's fields.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.s2.limbo.LimboPlugin.prepare,eu.nordtal.s2.limbo.LimboPlugin.enable",
        )
    }
}

dependencies {
    implementation(project(":limbo-protocol"))
    // jcore carries the config system and the database stack; Flyway is excluded, since this plugin never migrates.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // The plugin base opens its pool through :settings, which only compiles against HikariCP.
    implementation(libs.hikaricp)

    // :common's JdbiAccessDirectory installs JDBI's PostgresPlugin; jcore declares jdbi3-postgres at runtime only.
    implementation(libs.jdbi.postgres)

    // The settings, loaded in a test as the plugin loads them.
    testImplementation(testFixtures(project(":settings")))
}

messageSpec {
    specClass.set("eu.nordtal.s2.limbo.LimboMessages")
}
