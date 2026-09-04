// A library compiled against the Paper API but not itself a plugin.
//
// WHY THIS EXISTS, added 2026-09-04. `:common` is deliberately compiled against no platform at all
// (see its own build file and CLAUDE.md), which is the rule that keeps one shared module usable by
// Paper, Velocity and two plain JVM applications at once. The cost of that rule had been paid three
// times over in copies: anything the three Paper plugins do identically - a PermissionAttachment,
// an operator adapter, a Brigadier tree built from a shared declaration - had nowhere to live but
// each plugin's own source tree.
//
// So this is the missing layer, and it is deliberately NOT `nordtal.paper-plugin`: that convention
// brings run-paper, a ${version} expansion for a descriptor this has none of, and a shadowJar. A
// library is shaded by whoever consumes it.
//
// The rule that comes with it: code belongs here only if it needs a Paper type. Anything that does
// not goes in `:common`, where the proxy and the bot can reach it too. A class moved here to be
// "closer to where it is used" is a class two of the five processes just lost.

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
