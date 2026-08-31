pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "season-2"

// Optional composite build against a jcore checkout sitting next to this repo, for working on
// both at once without publishing a tag first. Off by default; turn it on with
//   ./gradlew <task> -PuseLocalJcore
// jcore's own coordinate is eu.nordtal:jcore - JitPack rewrites the group to com.github.nordtal -
// so the substitution has to be spelled out.
if (providers.gradleProperty("useLocalJcore").isPresent) {
    val jcore = file("../jcore")
    require(jcore.isDirectory) { "-PuseLocalJcore was set but $jcore does not exist" }
    includeBuild(jcore) {
        dependencySubstitution {
            substitute(module("com.github.nordtal:jcore")).using(project(":"))
        }
    }
}

// Paper plugins, one per backend server of the season 2 network.
include("limbo")
include("hunger-games")
include("smp")

// Velocity plugin on the proxy.
include("network-control")

// Standalone JVM application.
include("access-bot")

// Shared code, shaded into the plugins that use it.
include("common")

// Non-Java module: packs src/ into the resource pack zip.
include("resource-pack")
