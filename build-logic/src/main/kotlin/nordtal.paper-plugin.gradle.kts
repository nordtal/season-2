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
}

tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion("26.2")
}

// paper-plugin.yml carries ${version} so the descriptor never drifts from gradle.properties.
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}
