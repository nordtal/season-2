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
