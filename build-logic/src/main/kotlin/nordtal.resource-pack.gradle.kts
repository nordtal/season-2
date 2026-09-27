// Packs src/ into the resource pack zip and writes its SHA-1, which the client checks.

import eu.nordtal.s2.build.CheckNoDashPunctuation
import eu.nordtal.s2.build.CheckNoTrackerIds
import eu.nordtal.s2.build.CheckSourcesTracked
import eu.nordtal.s2.build.Sha1File

plugins {
    id("base")
}

val packZip =
    tasks.register<Zip>("packZip") {
        group = "distribution"
        description = "Packs src/ into the distributable resource pack zip."

        // The zip's root must be pack.mcmeta / assets, not a src/ folder.
        from(layout.projectDirectory.dir("src"))
        archiveBaseName.set("nordtal-resource-pack")
        archiveVersion.set(project.version.toString())

        // Byte-identical output for identical input, so the same version always hashes the same.
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }

val packSha1 =
    tasks.register<Sha1File>("packSha1") {
        group = "distribution"
        description = "Writes the SHA-1 of the pack zip next to it."

        source.set(packZip.flatMap { it.archiveFile })
        // `layout` stays outside the lambda: a provider closing over the script's scope cannot be cached.
        target.set(
            layout.buildDirectory.file(
                packZip.flatMap { it.archiveFileName }.map { "distributions/$it.sha1" },
            ),
        )
    }

packZip.configure { finalizedBy(packSha1) }

tasks.named("assemble") {
    dependsOn(packZip)
}

// The Java modules' guard, since an ignored file under src/ never reaches the zip.
val packSource = layout.projectDirectory.dir("src")
val repositoryRootDirectory = rootProject.layout.projectDirectory

val checkSourcesTracked =
    tasks.register<CheckSourcesTracked>("checkSourcesTracked") {
        sourceDirectories.from(packSource)
        repositoryRoot.set(repositoryRootDirectory)
    }

tasks.named("check") {
    dependsOn(checkSourcesTracked)
}

val checkNoTrackerIds =
    tasks.register<CheckNoTrackerIds>("checkNoTrackerIds") {
        repositoryRoot.set(rootProject.layout.projectDirectory)
        pathspecs.set(
            listOf(
                rootProject.projectDir
                    .toPath()
                    .relativize(projectDir.toPath())
                    .toString(),
            ),
        )
        enforced.set(findProperty("conventions.comments")?.toString()?.toBoolean() ?: false)
    }

val noDashPunctuation =
    tasks.register<CheckNoDashPunctuation>("noDashPunctuation") {
        repositoryRoot.set(rootProject.layout.projectDirectory)
        pathspecs.set(
            listOf(
                rootProject.projectDir
                    .toPath()
                    .relativize(projectDir.toPath())
                    .toString(),
            ),
        )
        enforced.set(findProperty("conventions.comments")?.toString()?.toBoolean() ?: false)
    }

tasks.named("check") {
    dependsOn(checkNoTrackerIds, noDashPunctuation)
}
