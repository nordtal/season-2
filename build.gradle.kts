// No artifact of its own. Shared configuration lives in build-logic, never in `subprojects {}`.

// `base` gives the root a `check` task for the script tests below.
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
        ":steward:shadowJar",
        ":resource-pack:packZip",
    )
}

// Fills what the image Dockerfiles COPY: jars from `build/libs` and the agent's `build/compose`.
tasks.register("imageContexts") {
    group = "distribution"
    description = "Builds what the image Dockerfiles COPY, so `docker build` has something to find."
    dependsOn(
        ":discord-bot:shadowJar",
        ":steward:shadowJar",
        ":steward-bunq:shadowJar",
        // `build`, not `shadowJar`: the agent's context is the jar AND the staged compose.yml.
        ":steward-agent:build",
    )
}

// The entrypoint decides whether a world folder is deleted.
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

// nordtal.sh compares the domain's address with this host's; too lenient and no certificate is issued.
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

// deploy/restore.sh empties a volume before it fills it.
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

// Scans every `pipefail` script under deploy/ for an early-exiting pipe reader such as `| head`.
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

// These four suites need bash 4; a machine without it, such as Windows or a stock Mac, skips them.
listOf(checkEntrypoint, checkSetup, checkRestore, checkPipeSafety).forEach { suite ->
    suite.configure {
        onlyIf("bash 4 or later is on the PATH") {
            eu.nordtal.s2.build.Bash
                .atLeast4()
        }
    }
}

tasks.named("check") { dependsOn(checkEntrypoint, checkSetup, checkRestore, checkPipeSafety) }
