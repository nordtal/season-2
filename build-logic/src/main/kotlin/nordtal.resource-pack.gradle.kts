// Not a Java module. Its only job is packing src/ into the zip the release ships and the
// pack-install server serves.
//
// The client is sent a URL *and* a SHA-1; Minecraft refuses the pack if they disagree, so the
// hash is generated on every build rather than written down anywhere.

import eu.nordtal.s2.build.CheckSourcesTracked
import eu.nordtal.s2.build.Sha1File

plugins {
    id("base")
}

val packZip = tasks.register<Zip>("packZip") {
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

val packSha1 = tasks.register<Sha1File>("packSha1") {
    group = "distribution"
    description = "Writes the SHA-1 of the pack zip next to it."

    source.set(packZip.flatMap { it.archiveFile })
    // `layout` stays outside the lambda: a provider that closes over the script's scope
    // cannot be serialized into the configuration cache.
    target.set(layout.buildDirectory.file(
        packZip.flatMap { it.archiveFileName }.map { "distributions/$it.sha1" }
    ))
}

packZip.configure { finalizedBy(packSha1) }

tasks.named("assemble") {
    dependsOn(packZip)
}

// The same guard the Java modules get. This module has no source sets, but it has a src/ whose
// contents go into the zip, and an ignored file there is a glyph the client never receives.
val packSource = layout.projectDirectory.dir("src")
val repositoryRootDirectory = rootProject.layout.projectDirectory

val checkSourcesTracked = tasks.register<CheckSourcesTracked>("checkSourcesTracked") {
    sourceDirectories.from(packSource)
    repositoryRoot.set(repositoryRootDirectory)
}

tasks.named("check") {
    dependsOn(checkSourcesTracked)
}
