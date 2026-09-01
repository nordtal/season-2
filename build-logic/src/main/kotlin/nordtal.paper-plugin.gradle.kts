// A Paper plugin for one of the season 2 backend servers.
// Adds the Paper API, the shared code, and a local test server via run-paper.

import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("nordtal.shaded")
    id("xyz.jpenilla.run-paper")
}

val libs = the<LibrariesForLibs>()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    "compileOnly"(libs.paper.api)
    "implementation"(project(":common"))

    // The same API on the test classpath, and NOT so that tests can start a server - they cannot,
    // and "it compiles" is not verification in this repository. It is here for the parts of the API
    // that are plain values: Adventure components, which every player-facing string in these
    // modules is built out of, and which are otherwise impossible to assert without a server they
    // do not need. A test that reaches past those into Bukkit is a test that should not exist.
    "testImplementation"(libs.paper.api)
}

tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion("26.2")
}

// Paper 26.2 ships Gson and SnakeYAML in its own libraries/ directory (gson 2.14.0,
// snakeyaml 2.6) and the plugin classloader resolves both - verified on a running 26.2 server
// on 2026-08-30 by calling Class.forName("com.google.gson.Gson") and
// Class.forName("org.yaml.snakeyaml.Yaml") from onEnable. jcore's config system needs them, but
// bundling a second copy in every plugin jar only adds ~700 KB per plugin and invites a version
// clash with the platform's own. They are excluded here and NOT in nordtal.jvm-app, which has no
// platform to provide them.
//
// If a future Paper release drops either of them, this exclusion is what breaks - re-run that
// Class.forName check before bumping the platform.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    dependencies {
        exclude(dependency("com.google.code.gson:gson"))
        exclude(dependency("org.yaml:snakeyaml"))
    }
}

// paper-plugin.yml carries ${version} so the descriptor never drifts from gradle.properties.
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}
