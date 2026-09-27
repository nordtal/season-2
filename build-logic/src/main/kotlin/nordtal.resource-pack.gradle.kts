// Assembles src/ and the fonts generated from templates/ into one pack, zips it with its SHA-1, which the
// client checks, and installs it into a local Minecraft instance.

import eu.nordtal.s2.build.CheckNoDashPunctuation
import eu.nordtal.s2.build.CheckNoTrackerIds
import eu.nordtal.s2.build.CheckPack
import eu.nordtal.s2.build.CheckSourcesTracked
import eu.nordtal.s2.build.GenerateRowFonts
import eu.nordtal.s2.build.InstallPack
import eu.nordtal.s2.build.MinecraftInstanceChooser
import eu.nordtal.s2.build.Sha1File

plugins {
    id("base")
}

val packSource = layout.projectDirectory.dir("src")
val templates = layout.projectDirectory.dir("templates")

// The six chest-row fonts are row 0 at six heights; pitch and count mirror SlotGeometry and MenuTitle.
val generateRowFonts =
    tasks.register<GenerateRowFonts>("generateRowFonts") {
        template.set(templates.file("gui_row.json"))
        rows.set(6)
        pitch.set(18)
        target.set(layout.buildDirectory.dir("generated/row-fonts"))
    }

// The pack as the client receives it; everything that reads the pack reads this, never src/.
val assembledPack = layout.buildDirectory.dir("pack")
val assemblePack =
    tasks.register<Sync>("assemblePack") {
        group = "build"
        description = "Assembles src/ and the generated fonts into the resource pack."
        from(packSource)
        from(generateRowFonts)
        into(assembledPack)
    }

// For the Java modules whose tests and resources are derived from the pack.
configurations.consumable("pack") {
    outgoing.artifact(assembledPack) { builtBy(assemblePack) }
}

val packZip =
    tasks.register<Zip>("packZip") {
        group = "distribution"
        description = "Packs the assembled pack into the distributable resource pack zip."

        // The zip's root must be pack.mcmeta / assets, not a src/ folder.
        from(assemblePack)
        archiveBaseName.set("nordtal-resource-pack")
        archiveVersion.set(project.version.toString())

        // Reproducible, so the same version always hashes the same.
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
val repositoryRootDirectory = rootProject.layout.projectDirectory

val checkSourcesTracked =
    tasks.register<CheckSourcesTracked>("checkSourcesTracked") {
        sourceDirectories.from(packSource, templates)
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

val checkPack =
    tasks.register<CheckPack>("checkPack") {
        dependsOn(assemblePack)
        assets.set(assembledPack.map { it.dir("assets") })
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
    pack.set(assembledPack)
    instanceFile.set(minecraftInstance)
    packName.set("nordtal-dev")
}
