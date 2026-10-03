package eu.nordtal.s2.build

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import kotlin.math.abs

/**
 * Writes everything derived from the [GlyphAllocation]: the font files, the Java class of code points, the advance
 * tables the plugins carry and the glyph menu of Steward's translation editor.
 *
 * One task reads the allocation once, so the four can never disagree, and none of them is committed.
 */
@CacheableTask
abstract class GenerateGlyphs : DefaultTask() {
    /** `resource-pack/glyphs.json`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allocation: RegularFileProperty

    /** The pack's `src/assets`, holding the textures the allocation names. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assets: DirectoryProperty

    /** The fully qualified name of the Java class to write. */
    @get:Input
    abstract val className: Property<String>

    /** Font id to the resource path its advance table is written at. */
    @get:Input
    abstract val advanceTables: MapProperty<String, String>

    /** A pack root: the fonts land in `assets/<namespace>/font/`. */
    @get:OutputDirectory
    abstract val fontDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val javaDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val resourceDirectory: DirectoryProperty

    /** `manifest.json` and one `<name>.png` per named glyph. */
    @get:OutputDirectory
    abstract val manifestDirectory: DirectoryProperty

    init {
        group = "build"
        description = "Writes the fonts, Glyphs, the advance tables and the glyph manifest from glyphs.json."
    }

    @TaskAction
    fun generate() {
        val glyphs =
            try {
                GlyphAllocation.read(allocation.get().asFile)
            } catch (e: IllegalStateException) {
                throw GradleException(e.message ?: "glyphs.json cannot be read", e)
            }
        val fonts = writeFonts(glyphs)
        writeAdvances(fonts)
        writeJava(glyphs)
        writeManifest(glyphs)
    }

    /** Returns each written font file by its id. */
    private fun writeFonts(glyphs: GlyphAllocation): Map<String, File> {
        val root = fresh(fontDirectory.get().asFile)
        val written = LinkedHashMap<String, File>()
        for (font in glyphs.fonts) {
            font.ids.forEachIndexed { row, id ->
                val providers = mutableListOf<Map<String, Any>>()
                if (font.spaces.isNotEmpty()) {
                    val advances = LinkedHashMap<String, Int>()
                    font.spaces.forEach { advances[Character.toString(it.codePoint)] = it.advance }
                    providers += linkedMapOf("type" to "space", "advances" to advances)
                }
                for (block in font.blocks) {
                    val lower = row * font.pitch
                    if (block.texture != null) {
                        val chars = block.chars ?: listOf(block.glyphs.joinToString("") { Character.toString(it.codePoint) })
                        providers += bitmap(block.texture, block.ascent - lower, block.height, chars)
                    } else {
                        block.glyphs.forEach {
                            providers += bitmap(it.texture, it.ascent - lower, it.height, listOf(Character.toString(it.codePoint)))
                        }
                    }
                }
                val file = root.resolve(fontPath(id))
                file.parentFile.mkdirs()
                file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("providers" to providers))) + "\n")
                written[id] = file
            }
        }
        return written
    }

    private fun bitmap(
        texture: String,
        ascent: Int,
        height: Int,
        chars: List<String>,
    ): Map<String, Any> = linkedMapOf("type" to "bitmap", "file" to texture, "ascent" to ascent, "height" to height, "chars" to chars)

    private fun writeAdvances(fonts: Map<String, File>) {
        val root = fresh(resourceDirectory.get().asFile)
        for ((id, resource) in advanceTables.get()) {
            val font = fonts[id] ?: throw GradleException("an advance table is asked for font $id, which glyphs.json does not declare")
            val table = GlyphAdvances.of(font, assets.get().asFile)
            val lines =
                listOf(
                    "# Generated from resource-pack ${fontPath(id).removePrefix("assets/")} by the build - do not edit.",
                    "# How far each code point moves the cursor, in pixels.",
                ) + table.toSortedMap().map { (codePoint, advance) -> "%X=%d".format(codePoint, advance) }
            root.resolve(resource).apply {
                parentFile.mkdirs()
                writeText(lines.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun writeManifest(glyphs: GlyphAllocation) {
        val root = fresh(manifestDirectory.get().asFile)
        val named = glyphs.fonts.filter { it.id == GlyphAllocation.DEFAULT_FONT }.flatMap { it.glyphs }
        val entries =
            named.map { glyph ->
                val name = glyph.name!!
                GlyphAdvances.texture(assets.get().asFile, glyph.texture).copyTo(root.resolve("$name.png"))
                linkedMapOf(
                    "name" to name,
                    "codePoint" to glyph.codePoint,
                    "height" to glyph.height,
                    "ascent" to glyph.ascent,
                    "image" to "$name.png",
                )
            }
        root.resolve("manifest.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(entries)) + "\n")
    }

    private fun writeJava(glyphs: GlyphAllocation) {
        val qualified = className.get()
        val java = JavaWriter()
        java.line("package ${qualified.substringBeforeLast('.')};")
        java.line()
        java.line("import java.util.List;")
        java.line("import java.util.Map;")
        java.line()
        java.doc(
            "",
            "Code points of the characters the resource pack defines, grouped by font.",
            "The build writes this class from resource-pack/glyphs.json, which owns the allocation. Each font " +
                "allocates on its own, so a component drawing one of these names its font.",
        )
        java.line("public final class ${qualified.substringAfterLast('.')} {")
        java.line()
        java.line("    private ${qualified.substringAfterLast('.')}() {}")
        java.line()
        java.line("    private static String cp(final int codePoint) {")
        java.line("        return Character.toString(codePoint);")
        java.line("    }")
        for (font in glyphs.fonts) {
            java.line()
            java.line("    // === ${font.id} ===")
            font.constant?.let { constant ->
                java.line()
                if (font.rows == null) {
                    java.doc("    ", "The font id {@code ${font.id}}. ${font.about.orEmpty()}".trim())
                    java.line("    public static final String $constant = \"${font.id}\";")
                } else {
                    java.doc("    ", "The ${font.rows} font ids of {@code ${font.id}}, row 0 first. ${font.about.orEmpty()}".trim())
                    java.list("String", constant, font.ids.map { "\"$it\"" })
                }
            }
            val spaces = font.spaces.filter { it.constant != null }
            if (spaces.isNotEmpty()) {
                java.line()
                java.line("    // Space advances")
                for (space in spaces) {
                    val pixels = if (abs(space.advance) == 1) "pixel" else "pixels"
                    val direction = if (space.advance < 0) "back" else "forward"
                    java.line()
                    java.doc("    ", "Moves the cursor ${abs(space.advance)} $pixels $direction.")
                    java.line("    public static final String ${space.constant} = cp(0x%X);".format(space.codePoint))
                }
            }
            for (block in font.blocks.filter { it.glyphs.isNotEmpty() }) {
                java.line()
                java.line("    // ${block.title}, U+%X..U+%X".format(block.range!!.first, block.range.last))
                for (glyph in block.glyphs.filter { it.constant != null }) {
                    java.line()
                    glyph.about?.let { java.doc("    ", it) }
                    java.line("    public static final String ${glyph.constant} = cp(0x%X);".format(glyph.codePoint))
                }
            }
            for (list in font.lists) {
                java.line()
                list.about?.let { java.doc("    ", it) }
                val members = font.glyphs.filter { it.list == list.constant }
                java.list("String", list.constant, members.map { it.constant ?: "cp(0x%X)".format(it.codePoint) })
            }
        }
        val named = glyphs.fonts.filter { it.id == GlyphAllocation.DEFAULT_FONT }.flatMap { it.glyphs }
        java.line()
        java.doc(
            "    ",
            "Returns the glyphs of {@code ${GlyphAllocation.DEFAULT_FONT}} by the name a message writes as {@code <glyph:name>}.",
        )
        java.line("    public static Map<String, String> named() {")
        java.line("        return NAMED;")
        java.line("    }")
        java.line()
        java.line("    private static final Map<String, String> NAMED = Map.ofEntries(")
        named.forEachIndexed { index, glyph ->
            val value = glyph.constant ?: "cp(0x%X)".format(glyph.codePoint)
            java.line("            Map.entry(\"${glyph.name}\", $value)" + if (index == named.lastIndex) ");" else ",")
        }
        java.line("}")
        val file = fresh(javaDirectory.get().asFile).resolve(qualified.replace('.', '/') + ".java")
        file.parentFile.mkdirs()
        file.writeText(java.text())
    }

    /** Lines of Java source; a doc comment's prose is escaped for HTML and wrapped. */
    private class JavaWriter {
        private val out = StringBuilder()

        fun line(text: String = "") {
            out.append(text.trimEnd()).append('\n')
        }

        /** A doc comment of [paragraphs], each wrapped to the line width; code spans pass through unescaped. */
        fun doc(
            indent: String,
            vararg paragraphs: String,
        ) {
            val wrapped = paragraphs.map { wrap(escape(it), WIDTH - indent.length - 3) }
            if (wrapped.size == 1 && wrapped[0].size == 1 && indent.length + 8 + wrapped[0][0].length <= WIDTH) {
                line("$indent/** ${wrapped[0][0]} */")
                return
            }
            line("$indent/**")
            wrapped.forEachIndexed { index, lines ->
                if (index > 0) line("$indent *")
                lines.forEach { line("$indent * $it") }
            }
            line("$indent */")
        }

        fun list(
            type: String,
            constant: String,
            items: List<String>,
        ) {
            line("    public static final List<$type> $constant = List.of(")
            items.forEachIndexed { index, item -> line("            $item" + if (index == items.lastIndex) ");" else ",") }
        }

        fun text() = out.toString()

        private fun escape(text: String): String =
            text.split(CODE_SPAN).let { prose ->
                val spans = CODE_SPAN.findAll(text).map { it.value }.toList()
                prose
                    .mapIndexed { index, part ->
                        part.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + spans.getOrElse(index) { "" }
                    }.joinToString("")
            }

        private fun wrap(
            text: String,
            width: Int,
        ): List<String> {
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            for (word in text.split(' ').filter { it.isNotEmpty() }) {
                if (current.isNotEmpty() && current.length + 1 + word.length > width) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
            if (current.isNotEmpty()) lines += current.toString()
            return lines
        }

        companion object {
            private const val WIDTH = 120
            private val CODE_SPAN = Regex("""\{@code [^}]*}""")
        }
    }

    private companion object {
        /** `assets/<namespace>/font/<path>.json` for the font id `<namespace>:<path>`. */
        fun fontPath(id: String) = "assets/${id.substringBefore(':')}/font/${id.substringAfter(':')}.json"

        fun fresh(directory: File): File {
            directory.deleteRecursively()
            directory.mkdirs()
            return directory
        }
    }
}
