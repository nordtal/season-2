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

// Optional composite build against a jcore checkout next to this repo, for working on both at once
// without publishing a tag first. Off by default; turn it on with -PuseLocalJcore. JitPack rewrites
// jcore's group from eu.nordtal to com.github.nordtal, so the substitution has to be spelled out.
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
include("proxy")

// Standalone JVM applications.
include("discord-bot")

// The container that owns every version and the database schema.
include("steward-worker")

// Steward: the web interface and the one service allowed to create containers. Named after §8a of
// the concept - the module directory is the compose service name is the runtime identity.
include("steward-ui")
include("steward-deployer")

// Shared code, shaded into the plugins that use it.
include("common")

// Every command in the network, declared once and adapted per surface. Platform-free, like
// `:common` and for the same reason: Brigadier on two platforms and JDA on a third all consume it.
include("commands")

// Shared code that needs a Paper type, and therefore cannot live in `:common` - which is compiled
// against no platform on purpose. Shaded into the three Paper plugins.
include("paper-common")

// The architecture rules of CONVENTIONS.md, checked across every module's classes.
include("architecture")

// Non-Java module: packs src/ into the resource pack zip.
include("resource-pack")

// season-2-ops/29: two agents sharing this one working tree and calling `sh gradlew :common:test`
// at the same time write into the same `common/build/test-results/test/binary/` and tear each
// other's in-progress result file out from under the other - reproduced 2026-09-17 as exactly the
// ticket's `NoSuchFileException: .../in-progress-results-generic.bin` on one side and an
// `EOFException` on the other. No test ever failed; it is a directory collision, not a defect.
//
// `-PbuildRoot=<dir>` gives an invocation its own output tree: every project's
// `layout.buildDirectory` moves to `<dir>/<project path, without the leading colon, colons turned
// into slashes>` - `:common` to `<dir>/common`, the root project itself (whatever writes straight
// to the root's own `build/`, e.g. `checkEntrypoint`'s marker) to `<dir>/root-project`.
//
// Leaving the property unset changes nothing: every project's build directory stays exactly
// `<module>/build`, which is load-bearing and must stay the default - see the Dockerfiles under
// steward-ui/, steward-worker/, discord-bot/ and steward-deployer/, which `COPY build/libs/...`
// (and, for steward-deployer, `build/compose/compose.yml`) relative to the module directory and
// know nothing of this property. A delivery build - a release, or `docker compose build` - must
// never pass -PbuildRoot; verified 2026-09-17 that a build with it set leaves
// `<module>/build/libs/` byte-for-byte untouched (see season-2-ops/29 for the md5sum/mtime proof).
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
