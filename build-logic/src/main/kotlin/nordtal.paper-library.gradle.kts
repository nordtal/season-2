// A library against the Paper API, shaded by the plugins that consume it.
// Code belongs here only if it needs a Paper type; everything else goes in `:common`.

import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("nordtal.java-base")
    // java-library, so `:common` is on this module's API rather than hidden behind it.
    id("java-library")
}

val libs = the<LibrariesForLibs>()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    "compileOnly"(libs.paper.api)
    "api"(project(":common"))

    // On the test classpath for the plain-value parts of the API, never to start a server.
    "testImplementation"(libs.paper.api)
}
