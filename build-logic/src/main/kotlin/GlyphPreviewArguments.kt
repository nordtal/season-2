package eu.nordtal.season.build

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/** The arguments of GlyphPreviews in `:pack-rendering`: the glyph [font] and [code] as glyphs.json names them, and its drawn width. */
abstract class GlyphPreviewArguments : CommandLineArgumentProvider {
    /** The `assets` of the assembled pack, whose fonts and textures the width is derived from. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assets: DirectoryProperty

    @get:Input
    abstract val font: Property<String>

    @get:Input
    abstract val code: Property<String>

    /** The optional layout, such as `rows` or `text`, passed on as `--name=value`. */
    @get:Input
    abstract val options: MapProperty<String, String>

    @get:OutputFile
    abstract val out: RegularFileProperty

    override fun asArguments(): Iterable<String> {
        val width = width(assets.get().asFile, font.get(), code.get())
        return listOf("--font=${font.get()}", "--code=${code.get()}", "--width=$width") +
            options.get().map { (name, value) -> "--$name=$value" } +
            "--out=${out.get().asFile.absolutePath}"
    }

    companion object {
        /** Returns the drawn width of the glyph [code] in [font]: its [GlyphAdvances] advance less the trailing pixel. */
        fun width(
            assets: File,
            font: String,
            code: String,
        ): Int {
            val codePoint =
                code.toIntOrNull(16) ?: throw IllegalArgumentException("-Pcode=$code is not a hexadecimal code point such as FE000.")
            // The row fonts share one sheet, so the first of them answers for all.
            val id = font.replace("{row}", "0")
            val file = assets.resolve(id.substringBefore(':')).resolve("font").resolve(id.substringAfter(':') + ".json")
            require(file.isFile) { "The pack has no font $font." }
            val advance =
                GlyphAdvances.of(file, assets)[codePoint]
                    ?: throw IllegalArgumentException("The font $font has no glyph $code.")
            return advance - 1
        }
    }
}
