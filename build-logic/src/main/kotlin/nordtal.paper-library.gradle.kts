// A library compiled against the Paper API but not itself a plugin: the layer for what the three
// Paper plugins do identically, since `:common` is compiled against no platform. Not
// `nordtal.paper-plugin`, which brings run-paper, a descriptor expansion and a shadowJar that a
// library has no use for; it is shaded by whoever consumes it. Code belongs here only if it needs
// a Paper type - anything else goes in `:common`, where the proxy and the bot can reach it too.

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

    // Same reasoning as nordtal.paper-plugin: on the test classpath for the parts of the API that
    // are plain values, never to start a server.
    "testImplementation"(libs.paper.api)
}
