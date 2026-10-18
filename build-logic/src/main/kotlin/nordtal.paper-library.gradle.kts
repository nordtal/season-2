// A library against the Paper API. The plugins that use it shade it.
// Code belongs here only when it needs a Paper type. Everything else goes into the shared module that owns it.

import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("nordtal.java-base")
    // java-library puts the shared modules on the API of this module instead of hiding them.
    id("java-library")
}

val libs = the<LibrariesForLibs>()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    "compileOnly"(libs.paper.api)
    "api"(project(":pack-rendering"))

    // On the test classpath for the plain-value parts of the API. Tests never start a server.
    "testImplementation"(libs.paper.api)
}
