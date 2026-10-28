package eu.nordtal.season.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Reads or makes one change of [kind] to the pack's sources with the [options] that `resource-pack/README.md` lists per kind,
 * prints what it changed, and fails with the reason when it refuses. A [FieldRefusal] first prints `refused` and the option's
 * name, a [FieldsNeeded] `needs`, a field and its kind per line. Only the item kinds read the client jar.
 */
@DisableCachingByDefault(because = "Writes into the pack's sources")
abstract class EditPackSources : VanillaClientTask() {
    /**
     * `glyph`, `block`, `sheet`, `item`, `rename`, `edit`, `resize`, `delete`, `codePoints`, `animate`, `scaling`,
     * `readItem`, `writeItem`, `editItem`, `import`, `text`, `discard`, `resolve`, `conflict`, `publish` or `sync`.
     */
    @get:Input
    abstract val kind: Property<String>

    /** The `-P` properties of the run, such as `texture` and `width`. */
    @get:Input
    abstract val options: MapProperty<String, String>

    /** The resource pack's directory, with `glyphs.json` and `src/`. */
    @get:Internal
    abstract val pack: DirectoryProperty

    /** The clone, whose tracked files show where a glyph is used and whose refs hold main. */
    @get:Internal
    abstract val repository: DirectoryProperty

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
                "glyph",
                "to",
                "from",
                "coupled",
                "main",
                "definition",
                "model",
                "id",
                "status",
                "about",
                "frames",
                "scaling",
                "border",
                "stretchInner",
                "at",
                "fields",
                "add",
                "remove",
                "draft",
                "drafts",
                "keep",
                "gh",
                "key",
                "language",
                "value",
                "delete",
            )
    }

    @TaskAction
    fun create() {
        val options = options.get()

        fun text(key: String) = options[key] ?: throw FieldRefusal(key, "Set -P$key.")

        fun number(key: String) = options[key]?.let { it.toIntOrNull() ?: throw FieldRefusal(key, "-P$key=$it is not a whole number.") }

        fun numbers(key: String) =
            options[key]?.let { text ->
                text.split(',').map { it.trim().toIntOrNull() ?: throw FieldRefusal(key, "-P$key=$text is not a list of whole numbers.") }
            }

        val pack = pack.get().asFile
        val repository = repository.get().asFile
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

                    "rename" -> {
                        GlyphEdits.rename(pack, repository, text("glyph"), text("to"))
                    }

                    "edit" -> {
                        GlyphEdits.edit(pack, text("glyph"), options["status"], number("height"), number("ascent"), options["about"])
                    }

                    "animate" -> {
                        val animated =
                            TextureMeta.animate(
                                pack,
                                text("texture"),
                                numbers("frames") ?: throw FieldRefusal("frames", "Set -Pframes."),
                                number("from"),
                            )
                        animated.notices.forEach { logger.quiet(it) }
                        animated.written
                    }

                    "scaling" -> {
                        TextureMeta.scale(
                            pack,
                            text("texture"),
                            text("scaling"),
                            number("width"),
                            number("height"),
                            numbers("border")?.let { if (it.size == 1) List(4) { _ -> it[0] } else it },
                            options["stretchInner"] == "true",
                        )
                    }

                    "resize" -> {
                        GlyphEdits.resize(pack, text("glyph"), number("from") ?: text("from").toInt(), options["coupled"] != "false")
                    }

                    "delete" -> {
                        GlyphEdits.delete(pack, repository, text("glyph"))
                    }

                    "codePoints" -> {
                        val main = options["main"] ?: "origin/main"
                        val moves =
                            GlyphEdits.assignCodePoints(
                                pack,
                                git(repository, listOf("show", "$main:${pack.relativeTo(repository).invariantSeparatorsPath}/glyphs.json")),
                            )
                        return moves.forEach { logger.quiet(it) }
                    }

                    "readItem" -> {
                        return logger.quiet(JsonOutput.toJson(ItemFiles(pack, ::jar).use { it.describe(text("item"), Drafts.ids(pack)) }))
                    }

                    "writeItem" -> {
                        listOf(ItemFiles(pack, ::jar).use { it.write(text("item"), File(text("definition")).readText()) })
                    }

                    "editItem" -> {
                        listOf(
                            ItemFiles(pack, ::jar).use {
                                it.edit(text("item"), options["at"] ?: "", options["fields"], options["add"], options["remove"] == "true")
                            },
                        )
                    }

                    "import" -> {
                        ItemFiles(pack, ::jar).use { it.import(File(text("model")), text("id"), text("item"), options["at"] ?: "") }
                    }

                    "text" -> {
                        Texts.edit(pack, text("key"), options["language"], options["value"], options["delete"] == "true")
                    }

                    "discard" -> {
                        Drafts.discard(pack, text("draft"))
                    }

                    "resolve" -> {
                        val fields =
                            options["fields"]?.let { text ->
                                val json = runCatching { JsonSlurper().parseText(text) as? Map<*, *> }.getOrNull()
                                json?.entries?.associate { (field, side) -> field.toString() to side.toString() }
                                    ?: throw FieldRefusal("fields", "-Pfields=$text is no JSON object.")
                            }
                        Drafts.resolve(pack, text("draft"), options["keep"], fields.orEmpty())
                    }

                    "conflict" -> {
                        Drafts.readConflict(pack, text("draft"))
                    }

                    "publish" -> {
                        val url = Publish(repository, pack, options["gh"] ?: "gh").publish(text("drafts").split(','), text("title"))
                        return logger.quiet(url)
                    }

                    "sync" -> {
                        return Publish(repository, pack, options["gh"] ?: "gh").sync().forEach { logger.quiet(it) }
                    }

                    else -> {
                        throw GradleException("$kind is no kind of change.")
                    }
                }
            } catch (e: FieldRefusal) {
                logger.quiet("refused ${e.field}")
                throw GradleException(e.message ?: "The change is refused.", e)
            } catch (e: FieldsNeeded) {
                e.fields.forEach { (name, kind) -> logger.quiet("needs $name $kind") }
                throw GradleException(e.message.orEmpty(), e)
            } catch (e: IllegalArgumentException) {
                throw GradleException(e.message ?: "The change is refused.", e)
            } catch (e: IllegalStateException) {
                throw GradleException(e.message ?: "The change is refused.", e)
            }
        written.forEach { logger.quiet(it.absolutePath) }
    }
}
