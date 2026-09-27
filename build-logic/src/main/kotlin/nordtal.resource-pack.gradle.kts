// Not a Java module. It packs src/ into the zip the release ships and the pack-install server
// serves, and installs src/ into a local Minecraft instance for whoever draws the pack. The
// client is sent a URL and a SHA-1 and refuses the pack if they disagree, so the hash is
// generated on every build rather than written down anywhere.

import eu.nordtal.s2.build.CheckNoTrackerIds
import eu.nordtal.s2.build.CheckPack
import eu.nordtal.s2.build.CheckSourcesTracked
import eu.nordtal.s2.build.InstallPack
import eu.nordtal.s2.build.MinecraftInstanceChooser
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

// The same guard the Java modules get. This module has no source sets, but it has a src/ whose
// contents go into the zip, and an ignored file there is a glyph the client never receives.
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

tasks.named("check") {
    dependsOn(checkNoTrackerIds)
}

val checkPack =
    tasks.register<CheckPack>("checkPack") {
        assets.set(packSource.dir("assets"))
    }

tasks.named("check") {
    dependsOn(checkPack)
}

// Two tasks for whoever draws the pack: pick the game once, then copy the pack into it after every change.
val minecraftInstance = layout.projectDirectory.file("minecraft-instance.properties")

tasks.register<JavaExec>("chooseMinecraftInstance") {
    group = "resource pack"
    description = "Asks for the Minecraft instance the pack is installed into."
    mainClass.set(MinecraftInstanceChooser::class.java.name)
    classpath(
        MinecraftInstanceChooser::class.java.protectionDomain.codeSource.location
            .toURI(),
        KotlinVersion::class.java.protectionDomain.codeSource.location
            .toURI(),
    )
    args(minecraftInstance.asFile.absolutePath)
    argumentProviders.add(providers.gradleProperty("minecraftInstance").map { listOf(it) }.orElse(emptyList())::get)
    jvmArgs("-Dapple.awt.application.name=Nordtal")
}

tasks.register<InstallPack>("installPack") {
    dependsOn(checkPack)
    pack.set(packSource)
    instanceFile.set(minecraftInstance)
    packName.set("nordtal-dev")
}
