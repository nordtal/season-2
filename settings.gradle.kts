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

// -PuseLocalJcore builds against ../jcore; JitPack's group is com.github.nordtal, hence the substitution.
if (providers.gradleProperty("useLocalJcore").isPresent) {
    val jcore = file("../jcore")
    require(jcore.isDirectory) { "-PuseLocalJcore was set but $jcore does not exist" }
    includeBuild(jcore) {
        dependencySubstitution {
            substitute(module("com.github.nordtal:jcore")).using(project(":"))
        }
    }
}

// Paper plugins, one per backend server.
include("limbo")
include("hunger-games")
include("smp")

// Velocity plugin on the proxy.
include("proxy")

// Standalone JVM applications.
include("discord-bot")

// Owns every version and the database schema.
include("steward-worker")

// Steward's web interface and the one service allowed to create containers.
include("steward-ui")
include("steward-deployer")

// Shared code, shaded into the plugins that use it.
include("common")

// Every command in the network, declared once; platform-free like `:common`.
include("commands")

// Shared code that needs a Paper type, shaded into the three Paper plugins.
include("paper-common")

// The architecture rules of CONVENTIONS.md, checked across every module's classes.
include("architecture")

// Packs src/ into the resource pack zip.
include("resource-pack")

// -PbuildRoot=<dir> moves every build directory under <dir>, so parallel builds in one tree do not collide.
// Never pass it to a delivery build: the Dockerfiles COPY from each module's own `build/`.
val buildRoot = providers.gradleProperty("buildRoot")
if (buildRoot.isPresent) {
    val root = file(buildRoot.get())
    gradle.rootProject {
        allprojects {
            val relativePath = path.removePrefix(":").replace(':', '/')
            layout.buildDirectory.set(
                if (relativePath.isEmpty()) root.resolve("root-project") else root.resolve(relativePath),
            )
        }
    }
}
