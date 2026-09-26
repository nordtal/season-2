// season-2 produces no artifact of its own; every deployable is a module and a release attaches
// each module's own build output. Keep this file free of `subprojects {}` — shared configuration
// lives in build-logic's convention plugins.

// `base` gives the root project a `check` task, which `checkEntrypoint` below hangs off.
plugins {
    base
    id("nordtal.root-conventions")
}

tasks.register("releaseArtifacts") {
    group = "distribution"
    description = "Builds every artifact that goes onto a GitHub release."
    dependsOn(
        ":limbo:shadowJar",
        ":hunger-games:shadowJar",
        ":smp:shadowJar",
        ":proxy:shadowJar",
        ":discord-bot:shadowJar",
        ":steward-worker:shadowJar",
        ":resource-pack:packZip",
    )
}

// Four of the five image directories are empty until Gradle has run: their Dockerfiles COPY a jar
// out of `build/libs`, and steward-deployer also copies `compose.yml` out of `build/compose`.
// Separate from `releaseArtifacts` because none of steward-ui's or steward-deployer's output is a
// release asset.
tasks.register("imageContexts") {
    group = "distribution"
    description = "Builds what the image Dockerfiles COPY, so `docker build` has something to find."
    dependsOn(
        ":discord-bot:shadowJar",
        ":steward-worker:shadowJar",
        ":steward-ui:shadowJar",
        // `build`, not `shadowJar`: the deployer's context is the jar AND the staged compose.yml.
        ":steward-deployer:build",
    )
}

// `deploy/minecraft/entrypoint.sh` decides whether a world folder is deleted, on a container that
// starts by itself with no second chance to notice it decided wrong; see entrypoint-test.sh.
val entrypointScript = layout.projectDirectory.file("deploy/minecraft/entrypoint.sh")
val entrypointTest = layout.projectDirectory.file("deploy/minecraft/entrypoint-test.sh")

val checkEntrypoint =
    tasks.register<Exec>("checkEntrypoint") {
        group = "verification"
        description = "Runs deploy/minecraft/entrypoint-test.sh against fixture directories."
        // bash, not sh: the script and the entrypoint it sources both use BASH_SOURCE and [[ ]].
        commandLine("bash", entrypointTest.asFile.absolutePath)
        inputs.file(entrypointScript).withPropertyName("entrypoint")
        inputs.file(entrypointTest).withPropertyName("test")
        val marker = layout.buildDirectory.file("checkEntrypoint/passed")
        outputs.file(marker).withPropertyName("marker")
        doLast {
            marker
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText("passed\n")
        }
    }

// `deploy/dev reset` deletes a server's volume, which on smp is a hand-built world that is in no
// repository and in no release; dev-test.sh drives the guard that stops it.
val devScript = layout.projectDirectory.file("deploy/dev")
val devTest = layout.projectDirectory.file("deploy/dev-test.sh")

val checkDev =
    tasks.register<Exec>("checkDev") {
        group = "verification"
        description = "Runs deploy/dev-test.sh against deploy/dev's reset guard."
        commandLine("bash", devTest.asFile.absolutePath)
        inputs.file(devScript).withPropertyName("dev")
        inputs.file(devTest).withPropertyName("test")
        val marker = layout.buildDirectory.file("checkDev/passed")
        outputs.file(marker).withPropertyName("marker")
        doLast {
            marker
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText("passed\n")
        }
    }

// deploy/nordtal.sh waits until what the domain resolves to matches this host before deploying;
// getting that comparison wrong in the lenient direction produces a host whose certificate can
// never be issued, so nordtal-test.sh checks the comparison and the menu around it.
val setupScript = layout.projectDirectory.file("deploy/nordtal.sh")
val setupTest = layout.projectDirectory.file("deploy/nordtal-test.sh")

val checkSetup =
    tasks.register<Exec>("checkSetup") {
        group = "verification"
        description = "Runs deploy/nordtal-test.sh against deploy/nordtal.sh's checks."
        commandLine("bash", setupTest.asFile.absolutePath)
        inputs.file(setupScript).withPropertyName("setup")
        inputs.file(setupTest).withPropertyName("test")
        val marker = layout.buildDirectory.file("checkSetup/passed")
        outputs.file(marker).withPropertyName("marker")
        doLast {
            marker
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText("passed\n")
        }
    }

// deploy/restore.sh empties a volume before it fills it, the same hazard as `deploy/dev reset`.
val restoreScript = layout.projectDirectory.file("deploy/restore.sh")
val restoreTest = layout.projectDirectory.file("deploy/restore-test.sh")

val checkRestore =
    tasks.register<Exec>("checkRestore") {
        group = "verification"
        description = "Runs deploy/restore-test.sh against deploy/restore.sh's guards."
        commandLine("bash", restoreTest.asFile.absolutePath)
        inputs.file(restoreScript).withPropertyName("restore")
        inputs.file(restoreTest).withPropertyName("test")
        val marker = layout.buildDirectory.file("checkRestore/passed")
        outputs.file(marker).withPropertyName("marker")
        doLast {
            marker
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText("passed\n")
        }
    }

// Unlike the other four, this has no single script it tests: it scans every `pipefail` script
// under deploy/ for an early-exiting pipe reader (`| head`, `| grep -q`, and the like), which turns
// that reader's SIGPIPE into the whole pipeline's exit status. See deploy/pipe-safety-test.sh for
// the exception list of hits that are harmless.
val pipeSafetyTest = layout.projectDirectory.file("deploy/pipe-safety-test.sh")

val checkPipeSafety =
    tasks.register<Exec>("checkPipeSafety") {
        group = "verification"
        description = "Scans every pipefail script under deploy/ for an early-terminating pipe reader."
        commandLine("bash", pipeSafetyTest.asFile.absolutePath)
        // The whole directory: the script discovers its targets at run time.
        inputs.dir(layout.projectDirectory.dir("deploy")).withPropertyName("deploy")
        val marker = layout.buildDirectory.file("checkPipeSafety/passed")
        outputs.file(marker).withPropertyName("marker")
        doLast {
            marker
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText("passed\n")
        }
    }

tasks.named("check") { dependsOn(checkEntrypoint, checkDev, checkSetup, checkRestore, checkPipeSafety) }
