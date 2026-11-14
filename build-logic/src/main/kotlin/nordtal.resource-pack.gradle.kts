// Assembles src/ and the fonts from glyphs.json into one pack, zips it with its SHA-1, which the
// client checks, and installs it into a local Minecraft instance.

import eu.nordtal.season.build.CheckMarkdownHistory
import eu.nordtal.season.build.CheckNoDashPunctuation
import eu.nordtal.season.build.CheckNoTrackerIds
import eu.nordtal.season.build.CheckPack
import eu.nordtal.season.build.CheckSourcesTracked
import eu.nordtal.season.build.CopyVanillaTexture
import eu.nordtal.season.build.DescribePack
import eu.nordtal.season.build.DrawChestPanels
import eu.nordtal.season.build.EditMinecraftInstance
import eu.nordtal.season.build.EditPackSources
import eu.nordtal.season.build.ExtractVanillaFonts
import eu.nordtal.season.build.GenerateGlyphs
import eu.nordtal.season.build.InstallPack
import eu.nordtal.season.build.ListVanillaAssets
import eu.nordtal.season.build.MinecraftInstanceChooser
import eu.nordtal.season.build.MinecraftVersion
import eu.nordtal.season.build.VanillaClientTask
import eu.nordtal.season.build.WriteItemSprites
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("base")
    id("jvm-toolchains")
}

// Gson, which :pack-builder reads JSON with.
repositories {
    mavenCentral()
}

// :pack-builder writes the wheel, the vanilla facts and the zip, each task a run of its command line.
val packBuilder = configurations.dependencyScope("packBuilder")
val packBuilderClasspath =
    configurations.resolvable("packBuilderClasspath") {
        extendsFrom(packBuilder.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }
dependencies { "packBuilder"(project(":pack-builder")) }
val packBuilderLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val packBuilderRun: JavaExec.() -> Unit = {
    classpath(packBuilderClasspath)
    mainClass.set("eu.nordtal.season.packbuilder.PackBuilderMain")
    javaLauncher.set(packBuilderLauncher)
}

// The default wheel prizes, which :smp writes from DefaultSmp.
val wheelPrizes = configurations.dependencyScope("wheelPrizes")
val wheelPrizeFiles = configurations.resolvable("wheelPrizeFiles") { extendsFrom(wheelPrizes.get()) }
dependencies { "wheelPrizes"(project(":smp", "wheelPrizes")) }

// The off plate draws the plate's label from the wheel's own texts.
val wheelTexts = rootProject.layout.projectDirectory.file("smp/src/main/resources/documents/smp/wheel.json")

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
        // The table that Letters reads: vanilla's letters, the symbols and the spaces, as every ui text font draws them.
        advanceTables.put("nordtal:ui_text_0", "nordtal/ui/letter-advances.properties")
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

val drawChestPanels = tasks.register<DrawChestPanels>("drawChestPanels")

// The wooden wheel's turn for the default prizes. The vanilla facts come from the client jar further down.
val vanillaFacts = layout.buildDirectory.file("vanilla-facts/vanilla-facts.zip")
val generateWheel =
    tasks.register<JavaExec>("generateWheel") {
        description = "Writes the wooden wheel's pack files for the default prizes."
        packBuilderRun()
        val facts = vanillaFacts
        val prizes = wheelPrizeFiles.map { it.incoming.files }
        val texts = wheelTexts
        val pack = layout.buildDirectory.dir("generated/wheel/pack")
        inputs.file(facts).withPropertyName("facts").withPathSensitivity(PathSensitivity.NONE)
        inputs.files(prizes).withPropertyName("prizes").withPathSensitivity(PathSensitivity.NONE)
        inputs.file(texts).withPropertyName("texts").withPathSensitivity(PathSensitivity.NONE)
        outputs.dir(pack).withPropertyName("pack")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf("wheel", facts.get().asFile.path, prizes.get().singleFile.path, texts.asFile.path, pack.get().asFile.path)
            },
        )
    }

// The wheel's timetable, which the plugin reads. It depends on the turn alone and not on the prizes, so :smp, which
// writes the prizes on a classpath that holds this timetable, builds without a cycle.
val writeWheelTimetable =
    tasks.register<JavaExec>("writeWheelTimetable") {
        description = "Writes the wooden wheel's timetable for the plugin."
        packBuilderRun()
        val resources = layout.buildDirectory.dir("generated/wheel/resources")
        outputs.dir(resources).withPropertyName("resources")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "timetable",
                    resources
                        .get()
                        .file("nordtal/wheel/wheel.properties")
                        .asFile.path,
                )
            },
        )
    }

// The pack as the client receives it. Everything that reads the pack reads this and never src/.
val assembledPack = layout.buildDirectory.dir("pack")
val assemblePack =
    tasks.register<Sync>("assemblePack") {
        group = "build"
        description = "Assembles src/ and the generated fonts into the resource pack."
        from(packSource)
        from(generateGlyphs.flatMap { it.fontDirectory })
        from(drawChestPanels.flatMap { it.textures })
        from(generateWheel)
        into(assembledPack)
    }

// For the Java modules whose tests and resources derive from the pack.
configurations.consumable("pack") {
    outgoing.artifact(assembledPack) { builtBy(assemblePack) }
}

// The pack without the wheel, which steward-agent's image ships and draws the wheel into for the prizes it runs with.
val packBaseDirectory = layout.buildDirectory.dir("pack-base")
val assemblePackBase =
    tasks.register<Sync>("assemblePackBase") {
        description = "Assembles the pack without the wheel, for steward-agent's builds."
        from(packSource)
        from(generateGlyphs.flatMap { it.fontDirectory })
        from(drawChestPanels.flatMap { it.textures })
        into(packBaseDirectory)
    }
configurations.consumable("packBase") {
    outgoing.artifact(packBaseDirectory) { builtBy(assemblePackBase) }
}

// The pack as the client receives it, from the same sources as assemblePack. Its bytes depend on them alone, so the
// same version always has the same hash.
val packZipName = "nordtal-resource-pack-$version.zip"
val packZipFile = layout.buildDirectory.file("distributions/$packZipName")
val packZip =
    tasks.register<JavaExec>("packZip") {
        group = "distribution"
        description = "Packs the pack's sources and the wheel into the distributable resource pack zip."
        packBuilderRun()
        val facts = vanillaFacts
        val prizes = wheelPrizeFiles.map { it.incoming.files }
        val texts = wheelTexts
        val zip = packZipFile
        val roots =
            files(
                packSource,
                generateGlyphs.flatMap { it.fontDirectory },
                drawChestPanels.flatMap { it.textures },
            )
        inputs.file(facts).withPropertyName("facts").withPathSensitivity(PathSensitivity.NONE)
        inputs.files(prizes).withPropertyName("prizes").withPathSensitivity(PathSensitivity.NONE)
        inputs.file(texts).withPropertyName("texts").withPathSensitivity(PathSensitivity.NONE)
        inputs.files(roots).withPropertyName("roots").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(zip).withPropertyName("zip")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf("zip", facts.get().asFile.path, prizes.get().singleFile.path, texts.asFile.path, zip.get().asFile.path) +
                    roots.files.map { it.path }
            },
        )
    }

val packSha1 =
    tasks.register<JavaExec>("packSha1") {
        group = "distribution"
        description = "Writes the SHA-1 of the pack zip next to it."
        packBuilderRun()
        val zip = packZipFile
        val sha1 = layout.buildDirectory.file("distributions/$packZipName.sha1")
        inputs.file(zip).withPropertyName("zip").withPathSensitivity(PathSensitivity.NONE)
        outputs.file(sha1).withPropertyName("sha1")
        dependsOn(packZip)
        argumentProviders.add(CommandLineArgumentProvider { listOf("sha1", zip.get().asFile.path, sha1.get().asFile.path) })
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
    generated.from(drawChestPanels.flatMap { it.textures })
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
// The sounds are not in the jar but in the version's asset index, which :bridge-mod names from the same manifest.
val vanillaAssetIndex = configurations.dependencyScope("vanillaAssetIndex")
val vanillaAssetIndexFiles = configurations.resolvable("vanillaAssetIndexFiles") { extendsFrom(vanillaAssetIndex.get()) }
dependencies { "vanillaAssetIndex"(project(":bridge-mod", "vanillaAssetIndex")) }
val vanillaAssetCache = File(gradle.gradleUserHomeDir, "caches/nordtal/assets")
val vanillaClient: VanillaClientTask.() -> Unit = {
    version.set(minecraftVersion)
    client.from(vanillaClientFiles)
    assetIndex.from(vanillaAssetIndexFiles)
    assetCache.set(vanillaAssetCache)
}

// Vanilla's letter fonts, which the text fonts of glyphs.json point at and the build measures.
val extractVanillaFonts =
    tasks.register<ExtractVanillaFonts>("extractVanillaFonts") {
        vanillaClient()
        fonts.set(layout.buildDirectory.dir("vanilla-fonts"))
    }
generateGlyphs.configure { vanilla.set(extractVanillaFonts.flatMap { it.fonts }) }

// The chest window panels, drawn on every build from ChestPanelGeometry, so moving the panel is one number.
drawChestPanels.configure {
    vanillaClient()
    textures.set(layout.buildDirectory.dir("generated/chest-panels"))
}

// The few files of the client jar the wheel reads, in one zip, which steward-agent reads later as well.
val extractVanillaFacts =
    tasks.register<JavaExec>("extractVanillaFacts") {
        description = "Extracts the item definitions, the GUI display transforms and the letters the wheel reads."
        packBuilderRun()
        val client = vanillaClientFiles.map { it.incoming.files }
        val facts = vanillaFacts
        inputs.files(client).withPropertyName("client").withPathSensitivity(PathSensitivity.NONE)
        outputs.file(facts).withPropertyName("facts")
        argumentProviders.add(CommandLineArgumentProvider { listOf("facts", client.get().singleFile.path, facts.get().asFile.path) })
    }
generateWheel.configure { dependsOn(extractVanillaFacts) }
packZip.configure { dependsOn(extractVanillaFacts) }
configurations.consumable("vanillaFacts") {
    outgoing.artifact(vanillaFacts) { builtBy(extractVanillaFacts) }
}

// For :pack-rendering, which ships the wheel's timetable to the plugin that spins it.
configurations.consumable("wheelResources") {
    outgoing.artifact(layout.buildDirectory.dir("generated/wheel/resources")) { builtBy(writeWheelTimetable) }
}

// The atlas sprite of each item, for :pack-rendering, which ships the table that a menu draws an item's texture from.
val writeItemSprites =
    tasks.register<WriteItemSprites>("writeItemSprites") {
        vanillaClient()
        assets.set(packSource.dir("assets"))
        resources.set(layout.buildDirectory.dir("generated/item-sprites"))
    }
configurations.consumable("itemSprites") {
    outgoing.artifact(writeItemSprites.flatMap { it.resources })
}

tasks.register<ListVanillaAssets>("listVanillaAssets") {
    vanillaClient()
    listing.set(layout.buildDirectory.file("vanilla-assets.json"))
}

// Run with -Ptexture=minecraft:block/stone.png.
tasks.register<CopyVanillaTexture>("copyVanillaTexture") {
    vanillaClient()
    texture.set(providers.gradleProperty("texture"))
    assets.set(packSource.dir("assets"))
}

// Nordtal Designer creates and changes textures, items, texts and sounds with these. The README lists the -P options and the output of each.
val sourceEdits =
    mapOf(
        "createGlyph" to "glyph",
        "createGlyphBlock" to "block",
        "createGlyphSheet" to "sheet",
        "createItem" to "item",
        "createTexture" to "texture",
        "renameGlyph" to "rename",
        "editGlyph" to "edit",
        "resizeGlyph" to "resize",
        "deleteGlyph" to "delete",
        "createFont" to "font",
        "editFont" to "editFont",
        "editGlyphBlock" to "editBlock",
        "addSheetCells" to "cells",
        "deleteGlyphBlock" to "deleteBlock",
        "deleteFont" to "deleteFont",
        "deleteTexture" to "deleteTexture",
        "deleteItem" to "deleteItem",
        "assignCodePoints" to "codePoints",
        "animateTexture" to "animate",
        "scaleTexture" to "scaling",
        "readItem" to "readItem",
        "writeItem" to "writeItem",
        "editItem" to "editItem",
        "importBlockbench" to "import",
        "editModel" to "model",
        "exportBlockbench" to "export",
        "editText" to "text",
        "discardDraft" to "discard",
        "resolveConflict" to "resolve",
        "readConflict" to "conflict",
        "createSound" to "sound",
        "overrideSound" to "overrideSound",
        "editSound" to "editSound",
        "subtitleSound" to "subtitle",
        "deleteSound" to "deleteSound",
        "playSound" to "play",
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
