import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("nordtal.paper-library")
    id("nordtal.message-spec")
}

// NordtalPlugin's fields are set in start(), which onEnable() runs before anything can read them.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        option("NullAway:KnownInitializers", "eu.nordtal.season.papercommon.plugin.NordtalPlugin.start")
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
    // The base opens its pool by the driver's class name, so every plugin shades the driver as well.
    runtimeOnly(libs.postgresql.driver)

    // A server's settings, loaded in a test as the plugin loads them.
    testImplementation(testFixtures(project(":settings")))
    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.gson)
}

messageSpec {
    documentClasses.add("eu.nordtal.season.papercommon.chat.SystemLines")
    documentClasses.add("eu.nordtal.season.papercommon.command.CommandFilter")
    documentClasses.add("eu.nordtal.season.papercommon.command.Replies")
    documentClasses.add("eu.nordtal.season.papercommon.player.InventoryEditor")
    documentClasses.add("eu.nordtal.season.papercommon.player.Presence")
    documentClasses.add("eu.nordtal.season.papercommon.tab.TabList")
}
