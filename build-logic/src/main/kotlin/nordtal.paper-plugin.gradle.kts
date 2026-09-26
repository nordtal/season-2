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

    // Not so tests can start a server; it is here for the plain-value parts of the API, such as
    // Adventure components, that are otherwise impossible to assert without one.
    "testImplementation"(libs.paper.api)
}

tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion("26.2")
}

// Paper ships Gson and SnakeYAML in its own libraries/ directory and the plugin classloader
// resolves both, so a second copy in the plugin jar only adds size and invites a version clash.
// Not excluded in nordtal.jvm-app, which has no platform to provide them.
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
