// A library compiled against the Paper API but not itself a plugin: the layer for what the three
// Paper plugins do identically, since `:common` is deliberately compiled against no platform.
//
// Deliberately NOT `nordtal.paper-plugin`, which brings run-paper, a ${version} expansion for a
// descriptor this has none of, and a shadowJar. A library is shaded by whoever consumes it.
//
// Code belongs here only if it needs a Paper type. Anything else goes in `:common`, where the proxy
// and the bot can reach it too.

import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("nordtal.java-base")
    // java-library, so that `:common` is on the API of this module rather than hidden behind it:
    // a consumer of a Brigadier adapter built here also handles the Messages and NordtalUser types
    // it takes.
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
