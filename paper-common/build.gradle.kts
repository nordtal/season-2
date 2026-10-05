import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-library")
    id("nordtal.message-spec")
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

    // Every plugin shades both; the base only compiles against them.
    compileOnly(libs.hikaricp)
    compileOnly(libs.jdbi.core)

    // The assembled resource pack: the fonts the menu tests measure against only exist there.
    "resourcePack"(project(":resource-pack", "pack"))

    // A server's settings, loaded in a test as the plugin loads them.
    testImplementation(testFixtures(project(":settings")))
    testImplementation(testFixtures(project(":common")))
    testImplementation(testFixtures(project(":pack-rendering")))
    testImplementation(libs.gson)
}

messageSpec {
    specClasses.add("eu.nordtal.s2.papercommon.PaperCommonMessages")
}
