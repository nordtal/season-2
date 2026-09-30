import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-library")
    id("nordtal.message-spec")
}

repositories {
    maven("https://jitpack.io")
}

// NordtalPlugin's fields are set in start(), which onEnable() runs before anything can read them.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option("NullAway:KnownInitializers", "eu.nordtal.s2.papercommon.plugin.NordtalPlugin.start")
    }
}

dependencies {
    // `api`, since the Paper adapters here have NordtalUser on their signatures.
    api(project(":commands"))
    // `api`, since the plugin base hands its settings and its pool to the plugins built on it.
    api(project(":settings"))

    // Every plugin shades jcore, which carries both; the base only compiles against them.
    compileOnly(libs.hikaricp)
    compileOnly(libs.jdbi.core)
}

messageSpec {
    specClass.set("eu.nordtal.s2.papercommon.PaperCommonMessages")
}
