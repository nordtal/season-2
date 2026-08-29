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

// Paper plugins, one per backend server of the season 2 network.
include("resource-pack-coercion")
include("hunger-games")
include("smp-farm-world")

// Velocity plugin on the proxy.
include("network-control")

// Standalone JVM application.
include("payments-bot")

// Shared code, shaded into the plugins that use it.
include("common")

// Non-Java module: packs src/ into the resource pack zip.
include("resource-pack")
