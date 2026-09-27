package eu.nordtal.s2.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes one font's [GlyphAdvances] as a properties file the plugins read: hex code point to advance.
 *
 * The plugins compose a boss bar pill or a menu row and cannot read the pack, so they carry this
 * table. Deriving it here instead of committing it means a redrawn glyph cannot leave it stale.
 */
@CacheableTask
abstract class GenerateGlyphAdvances : DefaultTask() {
    /** The assembled pack: one directory holding `pack.mcmeta` and `assets`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pack: ConfigurableFileCollection

    /** The font file, relative to the pack's `assets`, e.g. `nordtal/font/bossbar.json`. */
    @get:Input
    abstract val font: Property<String>

    @get:OutputFile
    abstract val target: RegularFileProperty

    init {
        group = "build"
        description = "Derives a font's glyph advances from the resource pack."
    }

    @TaskAction
    fun generate() {
        val assetsDirectory = pack.singleFile.resolve("assets")
        val table = GlyphAdvances.of(assetsDirectory.resolve(font.get()), assetsDirectory)
        val lines =
            listOf(
                "# Generated from resource-pack ${font.get()} by the build - do not edit.",
                "# How far each code point moves the cursor, in pixels.",
            ) + table.toSortedMap().map { (codePoint, advance) -> "%X=%d".format(codePoint, advance) }
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}
