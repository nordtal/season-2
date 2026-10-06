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
        ":resource-pack:packZip",
    )
}

// Fills what deploy/jvm/Dockerfile COPYs: each image module's `build/image`.
tasks.register("imageContexts") {
    group = "distribution"
    description = "Builds what the image Dockerfiles COPY, so `docker build` has something to find."
    dependsOn(
        ":discord-bot:imageContext",
        ":steward:imageContext",
        ":steward-bunq:imageContext",
        ":steward-agent:imageContext",
    )
}

// Registers a shell suite under deploy/ that `check` runs with bash; up to date while its inputs are unchanged.
fun scriptSuite(
    name: String,
    description: String,
    test: String,
    inputs: TaskInputs.() -> Unit,
) = tasks.register<Exec>(name) {
    group = "verification"
    this.description = description
    commandLine(
        "bash",
        layout.projectDirectory
            .file(test)
            .asFile.absolutePath,
    )
    this.inputs.file(layout.projectDirectory.file(test)).withPropertyName("test")
    this.inputs.inputs()
    val marker = layout.buildDirectory.file("$name/passed")
    outputs.file(marker).withPropertyName("marker")
    // These suites need bash 4; a machine without it, such as Windows or a stock Mac, skips them.
    onlyIf("bash 4 or later is on the PATH") {
        eu.nordtal.season.build.Bash
            .atLeast4()
    }
    doLast {
        marker
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText("passed\n")
    }
}

// The entrypoint decides whether a world folder is deleted. bash, not sh: the test and the entrypoint
// it sources both use BASH_SOURCE and [[ ]].
val checkEntrypoint =
    scriptSuite(
        "checkEntrypoint",
        "Runs deploy/minecraft/entrypoint-test.sh against fixture directories.",
        "deploy/minecraft/entrypoint-test.sh",
    ) { file(layout.projectDirectory.file("deploy/minecraft/entrypoint.sh")).withPropertyName("entrypoint") }

// Every JVM service reads its own secrets through this one reader before the JVM starts.
val checkJvmSecrets =
    scriptSuite(
        "checkJvmSecrets",
        "Runs deploy/jvm/secrets-test.sh against the JVM image's secrets reader.",
        "deploy/jvm/secrets-test.sh",
    ) {
        file(layout.projectDirectory.file("deploy/jvm/secrets.sh")).withPropertyName("reader")
        file(layout.projectDirectory.file("deploy/jvm/entrypoint.sh")).withPropertyName("entrypoint")
    }

// nordtal.sh compares the domain's address with this host's; too lenient and no certificate is issued.
// The test also holds the script's profile list and uids equal to compose.yml's.
val checkSetup =
    scriptSuite(
        "checkSetup",
        "Runs deploy/nordtal-test.sh against deploy/nordtal.sh's checks.",
        "deploy/nordtal-test.sh",
    ) {
        file(layout.projectDirectory.file("deploy/nordtal.sh")).withPropertyName("setup")
        file(layout.projectDirectory.file("compose.yml")).withPropertyName("compose")
    }

// deploy/restore.sh empties a volume before it fills it.
val checkRestore =
    scriptSuite(
        "checkRestore",
        "Runs deploy/restore-test.sh against deploy/restore.sh's guards.",
        "deploy/restore-test.sh",
    ) { file(layout.projectDirectory.file("deploy/restore.sh")).withPropertyName("restore") }

// Scans every `pipefail` script under deploy/ for an early-exiting pipe reader such as `| head`. The
// whole directory, since the script discovers its targets at run time.
val checkPipeSafety =
    scriptSuite(
        "checkPipeSafety",
        "Scans every pipefail script under deploy/ for an early-terminating pipe reader.",
        "deploy/pipe-safety-test.sh",
    ) { dir(layout.projectDirectory.dir("deploy")).withPropertyName("deploy") }

tasks.named("check") { dependsOn(checkEntrypoint, checkJvmSecrets, checkSetup, checkRestore, checkPipeSafety) }
