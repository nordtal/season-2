// season-2 produces no artifact of its own. Every deployable is a module; a release
// attaches each module's own build output. Keep this file free of `subprojects {}` —
// shared configuration lives in build-logic's convention plugins.

// The `base` plugin gives the root project a `check` task, which `checkEntrypoint` below hangs off.
// It is the only reason it is applied - shared Java configuration still belongs in build-logic's
// convention plugins, never here.
plugins {
    base
}

tasks.register("releaseArtifacts") {
    group = "distribution"
    description = "Builds every artifact that goes onto a GitHub release."
    dependsOn(
        ":limbo:shadowJar",
        ":hunger-games:shadowJar",
        ":smp:shadowJar",
        ":network-control:shadowJar",
        ":discord-bot:shadowJar",
        // steward-worker is on the release like every other module, and for the same reason it
        // exists: its own version has to be movable by the mechanism it implements.
        ":steward-worker:shadowJar",
        ":resource-pack:packZip",
    )
}

// The five images are built from a module directory, and four of those directories are empty until
// Gradle has run: their Dockerfiles COPY a jar out of `build/libs`, and steward-deployer also
// copies `compose.yml` out of `build/compose`. `docker build` says `failed to compute cache key`
// when it is not there, which reads like a Dockerfile mistake rather than a missing build step.
//
// It is separate from `releaseArtifacts` because these are not release assets. Nothing here is
// attached to a release: steward-ui and steward-deployer exist only as images, and the bot's and
// the worker's jars are attached by `releaseArtifacts` for a different reason - the worker installs
// them out of the release into a volume.
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

// The deployment's shell is verified here and nowhere else.
//
// `deploy/minecraft/entrypoint.sh` decides whether a world folder is deleted, on a container that
// starts by itself, with no second chance to notice it decided wrong - see the header of
// entrypoint-test.sh. That is the one piece of this deployment where "run it and look" is too late,
// so it is the one piece with a test, and the test hangs off `check` like every other: `./gradlew
// build` locally, `build.yml` on every push, and `release.yml` before a jar is ever attached.
val entrypointScript = layout.projectDirectory.file("deploy/minecraft/entrypoint.sh")
val entrypointTest = layout.projectDirectory.file("deploy/minecraft/entrypoint-test.sh")

val checkEntrypoint = tasks.register<Exec>("checkEntrypoint") {
    group = "verification"
    description = "Runs deploy/minecraft/entrypoint-test.sh against fixture directories."
    // bash, not sh: the script and the entrypoint it sources both use BASH_SOURCE and [[ ]]. No
    // Docker and no network - the whole point is that it runs everywhere `check` does.
    commandLine("bash", entrypointTest.asFile.absolutePath)
    inputs.file(entrypointScript).withPropertyName("entrypoint")
    inputs.file(entrypointTest).withPropertyName("test")
    val marker = layout.buildDirectory.file("checkEntrypoint/passed")
    outputs.file(marker).withPropertyName("marker")
    doLast {
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText("passed\n")
    }
}

// The same arrangement for deploy/dev, and for the same reason: `deploy/dev reset` deletes a
// server's volume, which on smp is a hand-built world that is in no repository and in no release.
// The guard that stops it is two functions above dev's source guard, and dev-test.sh drives them.
val devScript = layout.projectDirectory.file("deploy/dev")
val devTest = layout.projectDirectory.file("deploy/dev-test.sh")

val checkDev = tasks.register<Exec>("checkDev") {
    group = "verification"
    description = "Runs deploy/dev-test.sh against deploy/dev's reset guard."
    commandLine("bash", devTest.asFile.absolutePath)
    inputs.file(devScript).withPropertyName("dev")
    inputs.file(devTest).withPropertyName("test")
    val marker = layout.buildDirectory.file("checkDev/passed")
    outputs.file(marker).withPropertyName("marker")
    doLast {
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText("passed\n")
    }
}

// And the third, for deploy/setup.sh. Its subject is not a deletion this time but a wait: §10 says
// a finished setup means everything works, which rests entirely on the comparison between what the
// name resolves to and what this host is. That comparison cannot be checked by running the script -
// the run either waits or it deploys - and getting it wrong in the lenient direction produces a
// host that deploys an interface whose certificate can never be issued.
val setupScript = layout.projectDirectory.file("deploy/setup.sh")
val setupTest = layout.projectDirectory.file("deploy/setup-test.sh")

val checkSetup = tasks.register<Exec>("checkSetup") {
    group = "verification"
    description = "Runs deploy/setup-test.sh against deploy/setup.sh's checks."
    commandLine("bash", setupTest.asFile.absolutePath)
    inputs.file(setupScript).withPropertyName("setup")
    inputs.file(setupTest).withPropertyName("test")
    val marker = layout.buildDirectory.file("checkSetup/passed")
    outputs.file(marker).withPropertyName("marker")
    doLast {
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText("passed\n")
    }
}

// And the fourth, for deploy/restore.sh - which is the same class of thing as `deploy/dev reset`
// and needs no separate argument: it empties a volume before it fills it, and one of those volumes
// is Nordtal.
val restoreScript = layout.projectDirectory.file("deploy/restore.sh")
val restoreTest = layout.projectDirectory.file("deploy/restore-test.sh")

val checkRestore = tasks.register<Exec>("checkRestore") {
    group = "verification"
    description = "Runs deploy/restore-test.sh against deploy/restore.sh's guards."
    commandLine("bash", restoreTest.asFile.absolutePath)
    inputs.file(restoreScript).withPropertyName("restore")
    inputs.file(restoreTest).withPropertyName("test")
    val marker = layout.buildDirectory.file("checkRestore/passed")
    outputs.file(marker).withPropertyName("marker")
    doLast {
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText("passed\n")
    }
}

tasks.named("check") { dependsOn(checkEntrypoint, checkDev, checkSetup, checkRestore) }
