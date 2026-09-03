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
        // The updater is on the release like every other module, and for the same reason it
        // exists: its own version has to be movable by the mechanism it implements.
        ":updater:shadowJar",
        ":resource-pack:packZip",
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

tasks.named("check") { dependsOn(checkEntrypoint) }
