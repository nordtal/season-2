package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Creates one texture of [kind] through [NewTextures], with the [options] that `resource-pack/README.md` lists per kind.
 *
 * It prints the absolute path of each file written, one per line, and fails with the reason when it refuses.
 */
@DisableCachingByDefault(because = "Writes into the pack's sources")
abstract class CreateTexture : DefaultTask() {
    /** `glyph`, `block`, `sheet` or `item`. */
    @get:Input
    abstract val kind: Property<String>

    /** The `-P` properties of the run, such as `texture` and `width`. */
    @get:Input
    abstract val options: MapProperty<String, String>

    /** The resource pack's directory, with `glyphs.json` and `src/`. */
    @get:Internal
    abstract val pack: DirectoryProperty

    init {
        group = "resource pack"
    }

    companion object {
        /** The `-P` properties that a kind may read. */
        val OPTIONS =
            setOf(
                "font",
                "block",
                "title",
                "texture",
                "constant",
                "name",
                "constants",
                "range",
                "size",
                "width",
                "height",
                "ascent",
                "item",
            )
    }

    @TaskAction
    fun create() {
        val options = options.get()

        fun text(key: String) = options[key] ?: throw GradleException("Set -P$key.")

        fun number(key: String) = options[key]?.let { it.toIntOrNull() ?: throw GradleException("-P$key=$it is not a whole number.") }

        val pack = pack.get().asFile
        val written =
            try {
                when (val kind = kind.get()) {
                    "glyph" -> {
                        NewTextures.glyph(
                            pack,
                            text("font"),
                            text("block"),
                            text("texture"),
                            options["constant"],
                            options["name"],
                            number("width") ?: text("width").toInt(),
                            number("height") ?: text("height").toInt(),
                            number("ascent"),
                        )
                    }

                    "block" -> {
                        NewTextures.block(
                            pack,
                            text("font"),
                            text("title"),
                            options["range"],
                            number("size") ?: 16,
                            number("height"),
                            number("ascent"),
                        )
                    }

                    "sheet" -> {
                        NewTextures.sheet(
                            pack,
                            text("font"),
                            text("title"),
                            text("texture"),
                            text("constants").split(',').map { it.trim() },
                            number("width") ?: text("width").toInt(),
                            number("height") ?: text("height").toInt(),
                            number("ascent") ?: text("ascent").toInt(),
                            options["range"],
                        )
                    }

                    "item" -> {
                        NewTextures.item(pack, text("item"), number("width") ?: 16, number("height") ?: 16)
                    }

                    else -> {
                        throw GradleException("$kind is no kind of texture.")
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw GradleException(e.message ?: "The texture cannot be created.", e)
            } catch (e: IllegalStateException) {
                throw GradleException(e.message ?: "The texture cannot be created.", e)
            }
        written.forEach { logger.quiet(it.absolutePath) }
    }
}
