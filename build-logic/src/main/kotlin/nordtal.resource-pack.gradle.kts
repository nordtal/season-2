// Assembles src/ and the fonts generated from glyphs.json into one pack, zips it with its SHA-1, which the
// client checks, and installs it into a local Minecraft instance.

import eu.nordtal.season.build.CheckNoDashPunctuation
import eu.nordtal.season.build.CheckNoTrackerIds
import eu.nordtal.season.build.CheckPack
import eu.nordtal.season.build.CheckSourcesTracked
import eu.nordtal.season.build.GenerateGlyphs
import eu.nordtal.season.build.InstallPack
import eu.nordtal.season.build.MinecraftInstanceChooser
import eu.nordtal.season.build.Sha1File

plugins {
    id("base")
}

val packSource = layout.projectDirectory.dir("src")

// The one allocation file: the fonts, Glyphs, the advance tables and Steward's glyph menu are all written from it.
val generatedGlyphs = layout.buildDirectory.dir("generated/glyphs")
val generateGlyphs =
    tasks.register<GenerateGlyphs>("generateGlyphs") {
        allocation.set(layout.projectDirectory.file("glyphs.json"))
        assets.set(packSource.dir("assets"))
        className.set("eu.nordtal.season.packrendering.Glyphs")
        // The paths BossBarAdvances and MenuFont read the tables from.
        advanceTables.put("nordtal:bossbar", "nordtal/hud/bossbar-advances.properties")
        advanceTables.put("nordtal:gui_r0", "nordtal/menu/gui-row-advances.properties")
        fontDirectory.set(generatedGlyphs.map { it.dir("pack") })
        javaDirectory.set(generatedGlyphs.map { it.dir("java") })
        resourceDirectory.set(generatedGlyphs.map { it.dir("resources") })
        manifestDirectory.set(generatedGlyphs.map { it.dir("manifest") })
    }

// For :pack-rendering, which compiles Glyphs and ships the advance tables, and for Steward's translation editor.
configurations.consumable("glyphJava") {
    outgoing.artifact(generateGlyphs.flatMap { it.javaDirectory })
}
configurations.consumable("glyphResources") {
    outgoing.artifact(generateGlyphs.flatMap { it.resourceDirectory })
}
configurations.consumable("glyphManifest") {
    outgoing.artifact(generateGlyphs.flatMap { it.manifestDirectory })
}

// The pack as the client receives it; everything that reads the pack reads this, never src/.
val assembledPack = layout.buildDirectory.dir("pack")
val assemblePack =
    tasks.register<Sync>("assemblePack") {
        group = "build"
        description = "Assembles src/ and the generated fonts into the resource pack."
        from(packSource)
        from(generateGlyphs.flatMap { it.fontDirectory })
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
