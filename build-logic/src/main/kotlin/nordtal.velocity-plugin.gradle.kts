// The Velocity proxy plugin. velocity-api ships the @Plugin annotation processor that
// generates velocity-plugin.json, so it is both compileOnly and an annotationProcessor.

import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("nordtal.shaded")
}

val libs = the<LibrariesForLibs>()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    "compileOnly"(libs.velocity.api)
    "annotationProcessor"(libs.velocity.api)
    "implementation"(project(":common"))
}

// Velocity's @Plugin annotation is compiled into velocity-plugin.json, so the version has to be
// baked into the source. Following Velocity's own recipe, the annotated class lives in
// src/main/templates and is expanded into a generated source directory, keeping gradle.properties
// the single source of truth for the version.
val generateTemplates = tasks.register<Copy>("generateTemplates") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    from(layout.projectDirectory.dir("src/main/templates"))
    into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
    expand(props)
}

sourceSets.named("main") {
    java.srcDir(generateTemplates)
}
