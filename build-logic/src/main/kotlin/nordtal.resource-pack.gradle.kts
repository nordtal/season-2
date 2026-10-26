// Assembles src/ and the fonts from glyphs.json into one pack, zips it with its SHA-1, which the
// client checks, and installs it into a local Minecraft instance.

import eu.nordtal.season.build.CheckMarkdownHistory
import eu.nordtal.season.build.CheckNoDashPunctuation
import eu.nordtal.season.build.CheckNoTrackerIds
import eu.nordtal.season.build.CheckPack
import eu.nordtal.season.build.CheckSourcesTracked
import eu.nordtal.season.build.CopyVanillaTexture
import eu.nordtal.season.build.DescribePack
import eu.nordtal.season.build.EditMinecraftInstance
import eu.nordtal.season.build.EditPackSources
import eu.nordtal.season.build.GenerateGlyphs
import eu.nordtal.season.build.InstallPack
import eu.nordtal.season.build.ListVanillaTextures
import eu.nordtal.season.build.MinecraftInstanceChooser
import eu.nordtal.season.build.MinecraftVersion
import eu.nordtal.season.build.Sha1File
import eu.nordtal.season.build.VanillaClientTask
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("base")
}

val packSource = layout.projectDirectory.dir("src")

// The .aseprite sources, each at the path of its PNG under src/. assemblePack copies src/ only, so art/ stays out of the pack.
val packArt = layout.projectDirectory.dir("art")

// The one allocation file. The build writes the fonts, Glyphs, the advance tables and the glyph menu of Steward from it.
val generatedGlyphs = layout.buildDirectory.dir("generated/glyphs")
val generateGlyphs =
    tasks.register<GenerateGlyphs>("generateGlyphs") {
        allocation.set(layout.projectDirectory.file("glyphs.json"))
        assets.set(packSource.dir("assets"))
        className.set("eu.nordtal.season.packrendering.Glyphs")
        // The paths where BossBarAdvances and MenuFont read the tables.
        advanceTables.put("nordtal:bossbar", "nordtal/hud/bossbar-advances.properties")
        advanceTables.put("nordtal:gui_r0", "nordtal/menu/gui-row-advances.properties")
        fontDirectory.set(generatedGlyphs.map { it.dir("pack") })
        javaDirectory.set(generatedGlyphs.map { it.dir("java") })
        resourceDirectory.set(generatedGlyphs.map { it.dir("resources") })
        manifestDirectory.set(generatedGlyphs.map { it.dir("manifest") })
    }

// For :pack-rendering, which compiles Glyphs and ships the advance tables, and for the translation editor of Steward.
configurations.consumable("glyphJava") {
    outgoing.artifact(generateGlyphs.flatMap { it.javaDirectory })
}
configurations.consumable("glyphResources") {
    outgoing.artifact(generateGlyphs.flatMap { it.resourceDirectory })
}
configurations.consumable("glyphManifest") {
    outgoing.artifact(generateGlyphs.flatMap { it.manifestDirectory })
}

// The pack as the client receives it. Everything that reads the pack reads this and never src/.
val assembledPack = layout.buildDirectory.dir("pack")
val assemblePack =
    tasks.register<Sync>("assemblePack") {
        group = "build"
        description = "Assembles src/ and the generated fonts into the resource pack."
        from(packSource)
        from(generateGlyphs.flatMap { it.fontDirectory })
        into(assembledPack)
    }

// For the Java modules whose tests and resources derive from the pack.
configurations.consumable("pack") {
    outgoing.artifact(assembledPack) { builtBy(assemblePack) }
}

val packZip =
    tasks.register<Zip>("packZip") {
        group = "distribution"
        description = "Packs the assembled pack into the distributable resource pack zip."

        // The root of the zip must be pack.mcmeta and assets, not a src/ folder.
        from(assemblePack)
        archiveBaseName.set("nordtal-resource-pack")
        archiveVersion.set(project.version.toString())

        // Reproducible, so the same version always has the same hash.
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }

val packSha1 =
    tasks.register<Sha1File>("packSha1") {
        group = "distribution"
        description = "Writes the SHA-1 of the pack zip next to it."

        source.set(packZip.flatMap { it.archiveFile })
        // `layout` stays outside the lambda, because a provider that closes over the scope of the script cannot be cached.
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

// The guard of the Java modules, because an ignored file under src/ never reaches the zip.
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

val checkMarkdownHistory =
    tasks.register<CheckMarkdownHistory>("checkMarkdownHistory") {
        repositoryRoot.set(rootProject.layout.projectDirectory)
        pathspecs.set(
            listOf(
                rootProject.projectDir
                    .toPath()
                    .relativize(projectDir.toPath())
                    .toString(),
            ),
        )
    }

tasks.named("check") {
    dependsOn(checkNoTrackerIds, noDashPunctuation, checkMarkdownHistory)
}

val checkPack =
    tasks.register<CheckPack>("checkPack") {
        dependsOn(assemblePack)
        assets.set(assembledPack.map { it.dir("assets") })
        art.set(packArt)
    }

tasks.named("check") {
    dependsOn(checkPack)
}

// Two tasks for the person who draws the pack: pick the game once, then copy the pack into it after every change.
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

// Nordtal Designer reads build/pack-model.json for its tree and its properties panel.
tasks.register<DescribePack>("describePack") {
    root.set(layout.projectDirectory)
    allocation.set(layout.projectDirectory.file("glyphs.json"))
    source.set(packSource)
    art.set(packArt)
    model.set(layout.buildDirectory.file("pack-model.json"))
}

tasks.register<InstallPack>("installPack") {
    dependsOn(checkPack)
    pack.set(assembledPack)
    instanceFile.set(minecraftInstance)
    packName.set("nordtal-dev")
}

// Nordtal Designer reads the chosen instance with describeInstance and installs the bridge mod with installBridgeMod -Pgh=<path>.
mapOf("describeInstance" to "describe", "installBridgeMod" to "bridge").forEach { (name, kind) ->
    tasks.register<EditMinecraftInstance>(name) {
        description =
            if (kind == "describe") "Prints the chosen Minecraft instance as JSON." else "Installs the bridge mod of the newest release."
        this.kind.set(kind)
        instanceFile.set(minecraftInstance)
        repository.set(rootProject.layout.projectDirectory)
        gh.set(providers.gradleProperty("gh"))
    }
}

// The client jar of the season's Minecraft version, the source of a vanilla override: the one that Fabric Loom
// downloads for :bridge-mod and checks against Mojang's version manifest.
val minecraftVersion = MinecraftVersion.of(the<LibrariesForLibs>().versions.paper.get())
val vanillaClientJar = configurations.dependencyScope("vanillaClient")
val vanillaClientFiles = configurations.resolvable("vanillaClientFiles") { extendsFrom(vanillaClientJar.get()) }
dependencies { "vanillaClient"(project(":bridge-mod", "vanillaClient")) }
val vanillaClient: VanillaClientTask.() -> Unit = {
    version.set(minecraftVersion)
    client.from(vanillaClientFiles)
}

tasks.register<ListVanillaTextures>("listVanillaTextures") {
    vanillaClient()
    listing.set(layout.buildDirectory.file("vanilla-textures.json"))
}

// Run with -Ptexture=minecraft:block/stone.png.
tasks.register<CopyVanillaTexture>("copyVanillaTexture") {
    vanillaClient()
    texture.set(providers.gradleProperty("texture"))
    assets.set(packSource.dir("assets"))
}

// Nordtal Designer creates and changes textures with these. The README lists the -P options and the output of each.
val sourceEdits =
    mapOf(
        "createGlyph" to "glyph",
        "createGlyphBlock" to "block",
        "createGlyphSheet" to "sheet",
        "createItem" to "item",
        "renameGlyph" to "rename",
        "editGlyph" to "edit",
        "resizeGlyph" to "resize",
        "deleteGlyph" to "delete",
        "assignCodePoints" to "codePoints",
        "animateTexture" to "animate",
        "scaleTexture" to "scaling",
        "readItem" to "readItem",
        "writeItem" to "writeItem",
        "editItem" to "editItem",
        "importBlockbench" to "import",
        "discardDraft" to "discard",
        "resolveConflict" to "resolve",
        "readConflict" to "conflict",
        "publishDrafts" to "publish",
        "syncReviews" to "sync",
    )
sourceEdits.forEach { (name, kind) ->
    tasks.register<EditPackSources>(name) {
        description = "Changes the pack's sources: $kind. The README lists its options and output."
        this.kind.set(kind)
        options.set(providers.gradlePropertiesPrefixedBy("").map { it.filterKeys { key -> key in EditPackSources.OPTIONS } })
        pack.set(layout.projectDirectory)
        repository.set(rootProject.layout.projectDirectory)
        vanillaClient()
    }
}
