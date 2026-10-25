package eu.nordtal.season.build

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI

/** A task that reads the client jar of [version], which [VanillaClient.jar] downloads once into [cache]. */
@DisableCachingByDefault(because = "The jar lives in a cache outside the build")
abstract class VanillaClientTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val manifest: Property<String>

    @get:Internal
    abstract val cache: DirectoryProperty

    init {
        group = "resource pack"
    }

    protected fun jar(): File = VanillaClient.jar(version.get(), URI(manifest.get()), cache.get().asFile)
}

/** Downloads the client jar once and prints its absolute path as the one line on stdout. */
@DisableCachingByDefault(because = "The jar lives in a cache outside the build")
abstract class DownloadVanillaClient : VanillaClientTask() {
    init {
        description = "Downloads the client jar of the season's Minecraft version once."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun download() = logger.quiet(jar().absolutePath)
}

/**
 * Writes every texture of the client jar to [listing] as JSON: `version` and `textures`, a list of `id`, `width`,
 * `height` and `mcmeta`, sorted by id. An id reads `minecraft:block/stone.png`, the path under `textures/`.
 */
@DisableCachingByDefault(because = "Reads a jar from a cache outside the build")
abstract class ListVanillaTextures : VanillaClientTask() {
    @get:OutputFile
    abstract val listing: RegularFileProperty

    init {
        description = "Lists the textures of the client jar with their sizes."
    }

    @TaskAction
    fun list() {
        val file = listing.get().asFile
        val textures = mapOf("version" to version.get(), "textures" to VanillaClient.textures(jar()))
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(textures)) + "\n")
        logger.quiet(file.absolutePath)
    }
}

/** Copies [texture] and its `.mcmeta` from the client jar to the override path under [assets], and prints each file written. */
@DisableCachingByDefault(because = "Writes into the pack's sources")
abstract class CopyVanillaTexture : VanillaClientTask() {
    /** An id that [ListVanillaTextures] lists, such as `minecraft:block/stone.png`. */
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
