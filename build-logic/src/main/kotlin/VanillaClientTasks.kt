package eu.nordtal.season.build

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * A task that reads [client], the one client jar of Minecraft [version]. Fabric Loom downloads it for `:bridge-mod` and
 * checks it against the SHA-1 of Mojang's version manifest, so no second download exists.
 */
@DisableCachingByDefault(because = "The jar lives in a cache outside the build")
abstract class VanillaClientTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Internal
    abstract val client: ConfigurableFileCollection

    init {
        group = "resource pack"
    }

    protected fun jar(): File = client.singleFile
}

/**
 * Writes every texture and item definition of the client jar to [listing] as JSON: `version`, `textures`, a list of
 * `id`, `width`, `height` and `mcmeta` sorted by id, and `items`, the sorted ids. A texture's id reads
 * `minecraft:block/stone.png`, the path under `textures/`. It prints the path of [listing] on every run.
 */
@DisableCachingByDefault(because = "Reads a jar from a cache outside the build")
abstract class ListVanillaAssets : VanillaClientTask() {
    @get:OutputFile
    abstract val listing: RegularFileProperty

    init {
        description = "Lists the textures of the client jar with their sizes, and its item definitions."
        // The path on stdout is what a caller reads, so the task runs every time.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun list() {
        val file = listing.get().asFile
        val assets =
            mapOf("version" to version.get(), "textures" to VanillaClient.textures(jar()), "items" to VanillaClient.items(jar()))
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(assets)) + "\n")
        logger.quiet(file.absolutePath)
    }
}

/** Copies [texture] and its `.mcmeta` from the client jar to the override path under [assets], and prints each file written. */
@DisableCachingByDefault(because = "Writes into the pack's sources")
abstract class CopyVanillaTexture : VanillaClientTask() {
    /** An id that [ListVanillaAssets] lists, such as `minecraft:block/stone.png`. */
    @get:Input
    abstract val texture: Property<String>

    @get:Internal
    abstract val assets: DirectoryProperty

    init {
        description = "Copies one texture of the client jar to its vanilla override path, as the start of a draft."
    }

    @TaskAction
    fun copy() = VanillaClient.copy(jar(), texture.get(), assets.get().asFile).forEach { logger.quiet(it.absolutePath) }
}
