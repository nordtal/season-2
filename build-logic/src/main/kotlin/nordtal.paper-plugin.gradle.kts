// A Paper plugin: the Paper API, the shared code and a local test server via run-paper.

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
    // Every shared module arrives through paper-common's API.
    "implementation"(project(":paper-common"))

    // On the test classpath for the plain-value parts of the API, never to start a server.
    "testImplementation"(libs.paper.api)
}

tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion("26.2")
}

// Paper provides Gson and SnakeYAML to plugins, so a shaded copy only invites a version clash.
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
