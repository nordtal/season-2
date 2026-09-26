import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-plugin")
    id("nordtal.message-spec")
}

repositories {
    // jcore is published via JitPack, not Maven Central.
    maven("https://jitpack.io")
}

// Names start() and its command-wiring helper as NullAway's initializers, since LimboPlugin's fields are set there.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option(
            "NullAway:KnownInitializers",
            "eu.nordtal.s2.limbo.LimboPlugin.start,eu.nordtal.s2.limbo.LimboPlugin.wireCommandInbox")
    }
}

dependencies {
    implementation(project(":paper-common"))
    // jcore carries the config system and the JDBI/HikariCP/PostgreSQL stack :common's AccessDirectory needs.
    // Flyway is excluded because this plugin never migrates anything and flyway-core drags in Jackson databind.
    implementation(libs.jcore) {
        exclude(group = "org.flywaydb")
    }

    // LimboPool builds its own HikariCP pool for the name, size and driver class AccessDirectory.open exposes none of.
    implementation(libs.hikaricp)

    // :common's JdbiAccessDirectory installs JDBI's PostgresPlugin; jcore declares jdbi3-postgres at runtime only.
    implementation(libs.jdbi.postgres)
}

messageSpec {
    specClass.set("eu.nordtal.s2.limbo.LimboMessages")
}
