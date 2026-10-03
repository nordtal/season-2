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
    // `api`, since the plugin base hands its inbox and its access reader to the plugins built on it.
    api(project(":database"))
    // `api`, since the plugin base hands its settings and its pool to the plugins built on it.
    api(project(":settings"))

    // Every plugin shades jcore, which carries both; the base only compiles against them.
    compileOnly(libs.hikaricp)
    // The shared sounds group is a spec; every plugin shades jcore, so the base only compiles against it.
    compileOnly(libs.jcore)
    compileOnly(libs.jdbi.core)

    // A server's settings, loaded in a test as the plugin loads them.
    testImplementation(testFixtures(project(":settings")))
    testImplementation(libs.jcore)
    testImplementation(libs.gson)
}

messageSpec {
    specClasses.add("eu.nordtal.s2.papercommon.PaperCommonMessages")
}
