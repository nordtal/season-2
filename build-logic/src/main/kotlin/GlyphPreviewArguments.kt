package eu.nordtal.season.build

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import javax.imageio.ImageIO

/** The arguments of GlyphPreviews in `:pack-rendering`: the glyph [font] and [code] as [GlyphAllocation] reads them, and its drawn width. */
abstract class GlyphPreviewArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allocation: RegularFileProperty

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
        val width = width(GlyphAllocation.read(allocation.get().asFile), assets.get().asFile, font.get(), code.get())
        return listOf("--font=${font.get()}", "--code=${code.get()}", "--width=$width") +
            options.get().map { (name, value) -> "--$name=$value" } +
            "--out=${out.get().asFile.absolutePath}"
    }

    companion object {
        /** Returns the drawn width of the glyph [code] in [font]: its PNG's width scaled to the glyph's height. */
        fun width(
            allocation: GlyphAllocation,
            assets: File,
            font: String,
            code: String,
        ): Int {
            val codePoint =
                code.toIntOrNull(16) ?: throw IllegalArgumentException("-Pcode=$code is not a hexadecimal code point such as FE000.")
            val glyph =
                allocation.fonts
                    .firstOrNull { it.id == font }
                    ?.glyphs
                    ?.firstOrNull { it.codePoint == codePoint }
                    ?: throw IllegalArgumentException("glyphs.json has no glyph $code in the font $font.")
            val image =
                ImageIO.read(GlyphAdvances.texture(assets, glyph.texture))
                    ?: throw IllegalStateException("${glyph.texture} is not an image ImageIO can read.")
            return (image.width * glyph.height + image.height / 2) / image.height
        }
    }
}
