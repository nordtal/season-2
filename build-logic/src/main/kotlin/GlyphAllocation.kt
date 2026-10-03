package eu.nordtal.s2.build

import groovy.json.JsonSlurper
import java.io.File

/**
 * `resource-pack/glyphs.json`, read and checked: every font, the code points it allocates and the art behind them.
 *
 * Everything the build writes about glyphs is derived from this one model, so a rule broken here fails the build
 * before any of it is written. The file's shape is documented in `resource-pack/README.md`.
 */
class GlyphAllocation private constructor(
    val fonts: List<Font>,
) {
    /** One font file, or six when [rows] is set: `{row}` in [id] is the row, and each row sits [pitch] lower. */
    class Font(
        val id: String,
        val rows: Int?,
        val pitch: Int,
        val constant: String?,
        val ownNumbering: Boolean,
        val about: String?,
        val spaces: List<Space>,
        val blocks: List<Block>,
        val lists: List<GlyphList>,
    ) {
        /** The font ids this entry writes, row 0 first. */
        val ids: List<String> get() = rows?.let { count -> (0 until count).map { id.replace(ROW, "$it") } } ?: listOf(id)

        /** Every glyph of every block, in file order. */
        val glyphs: List<Glyph> get() = blocks.flatMap { it.glyphs }
    }

    /** A `space` provider entry: the cursor moves by [advance] and nothing is drawn. */
    class Space(
        val codePoint: Int,
        val advance: Int,
        val constant: String?,
    )

    /**
     * A run of code points, [range], with one title; what it does not use is reserved for its growth.
     *
     * A block with a [texture] is one sheet: its glyphs are the cells of one row, or [chars] lists its rows of
     * ordinary characters, which have no range.
     */
    class Block(
        val range: IntRange?,
        val title: String,
        val about: String?,
        val texture: String?,
        val height: Int,
        val ascent: Int,
        val chars: List<String>?,
        val glyphs: List<Glyph>,
    )

    class Glyph(
        val codePoint: Int,
        val constant: String?,
        val name: String?,
        val list: String?,
        val texture: String,
        val height: Int,
        val ascent: Int,
        val about: String?,
    )

    /** A Java list of the glyphs that name it, in file order. */
    class GlyphList(
        val constant: String,
        val about: String?,
    )

    companion object {
        /** The font whose glyphs a message names as `<glyph:name>`. */
        const val DEFAULT_FONT = "minecraft:default"

        private const val ROW = "{row}"
        private val STATUSES = setOf("keep", "final candidate", "placeholder")
        private val SUPPLEMENTARY_PRIVATE_USE_A = 0xF0000..0xFFFFD
        private val FONT_ID = Regex("""[a-z0-9_.-]+:[a-z0-9_./-]+""")
        private val CONSTANT = Regex("""[A-Z][A-Z0-9_]*""")
        private val NAME = Regex("""[a-z0-9]+(-[a-z0-9]+)*""")
        private val CODE = Regex("""[0-9A-F]{2,5}""")

        /** Reads [file], or fails with every rule it breaks. */
        fun read(file: File): GlyphAllocation {
            val problems = mutableListOf<String>()
            val root = Node(JsonSlurper().parse(file), file.name, problems)
            val fonts = root.objects("fonts", required = true).map { readFont(it) }
            if (problems.isEmpty()) check(file.name, fonts, problems)
            if (problems.isNotEmpty()) {
                throw IllegalStateException(
                    "${file.name} breaks ${problems.size} rule(s):\n" + problems.joinToString("\n") { "    $it" },
                )
            }
            return GlyphAllocation(fonts)
        }

        private fun readFont(node: Node): Font {
            node.allow("font", "rows", "pitch", "constant", "numbering", "about", "spaces", "blocks", "lists")
            val id = node.string("font", required = true) ?: ""
            val font = node.named(id)
            val rows = font.int("rows")
            val pitch = font.int("pitch")
            if (!FONT_ID.matches(id.replace(ROW, "0"))) font.problem("'$id' is not a font id such as nordtal:board")
            if ((ROW in id) != (rows != null)) font.problem("'{row}' belongs in the id exactly when 'rows' is set")
            if ((pitch != null) != (rows != null)) font.problem("'pitch' is set exactly when 'rows' is")
            val numbering = font.string("numbering")
            if (numbering != null && numbering != "own") font.problem("'numbering' is either absent or \"own\"")
            return Font(
                id = id,
                rows = rows,
                pitch = pitch ?: 0,
                constant = font.string("constant"),
                ownNumbering = numbering == "own",
                about = font.string("about"),
                spaces = font.objects("spaces").map { readSpace(it) },
                blocks = font.objects("blocks", required = true).map { readBlock(it) },
                lists =
                    font.objects("lists").map {
                        it.allow("constant", "about")
                        GlyphList(it.string("constant", required = true) ?: "", it.string("about"))
                    },
            )
        }

        private fun readSpace(node: Node): Space {
            node.allow("code", "advance", "constant")
            val code = node.code("code") ?: 0
            if (code != ' '.code && code !in SUPPLEMENTARY_PRIVATE_USE_A) {
                node.problem("a space is the ordinary space or lies in Supplementary Private Use Area-A")
            }
            return Space(code, node.int("advance", required = true) ?: 0, node.string("constant"))
        }

        private fun readBlock(node: Node): Block {
            node.allow("range", "title", "about", "texture", "height", "ascent", "status", "chars", "glyphs")
            val title = node.string("title", required = true) ?: ""
            val block = node.named(title)
            val texture = block.string("texture")
            val chars = block.strings("chars")
            val range = block.string("range")?.let { readRange(block, it) }
            if (chars != null && (texture == null || range != null || block.has("glyphs"))) {
                block.problem("'chars' is a sheet of ordinary characters: it needs 'texture', and no 'range' or 'glyphs'")
            }
            if (chars == null && !block.has("range")) block.problem("'range' is missing")
            block.status()
            val height = block.int("height")
            val ascent = block.int("ascent")
            if (texture != null && (height == null || ascent == null)) {
                block.problem("a sheet sets 'height' and 'ascent' for all its cells")
            }
            val glyphs =
                block.objects("glyphs", required = chars == null).map {
                    readGlyph(it, texture, height, ascent)
                }
            chars?.map { it.codePointCount(0, it.length) }?.distinct()?.let {
                if (it.size > 1) block.problem("every row of 'chars' has the same number of cells")
            }
            return Block(range, title, block.string("about"), texture, height ?: 0, ascent ?: 0, chars, glyphs)
        }

        private fun readGlyph(
            node: Node,
            sheet: String?,
            blockHeight: Int?,
            blockAscent: Int?,
        ): Glyph {
            node.allow("code", "constant", "name", "list", "texture", "height", "ascent", "status", "about")
            val code = node.code("code") ?: 0
            val glyph = node.named("%X".format(code))
            if (code !in SUPPLEMENTARY_PRIVATE_USE_A) glyph.problem("a glyph lies in Supplementary Private Use Area-A")
            if (sheet != null && (glyph.has("texture") || glyph.has("height") || glyph.has("ascent"))) {
                glyph.problem("a cell of a sheet takes 'texture', 'height' and 'ascent' from its block")
            }
            val texture = sheet ?: glyph.string("texture", required = true) ?: ""
            val height = glyph.int("height") ?: blockHeight
            val ascent = glyph.int("ascent") ?: blockAscent
            if (sheet == null && (height == null || ascent == null)) {
                glyph.problem("'height' and 'ascent' are set here or on the block")
            }
            glyph.status()
            val constant = glyph.string("constant")
            val list = glyph.string("list")
            if (constant == null && list == null) glyph.problem("a glyph has a 'constant', a 'list', or both")
            return Glyph(code, constant, glyph.string("name"), list, texture, height ?: 0, ascent ?: 0, glyph.string("about"))
        }

        private fun readRange(
            block: Node,
            text: String,
        ): IntRange? {
            val bounds = text.split("-")
            if (bounds.size != 2 || bounds.any { !CODE.matches(it) }) {
                block.problem("'range' is two hex code points, such as FE000-FE00F")
                return null
            }
            val range = bounds[0].toInt(16)..bounds[1].toInt(16)
            if (range.isEmpty() || range.first !in SUPPLEMENTARY_PRIVATE_USE_A || range.last !in SUPPLEMENTARY_PRIVATE_USE_A) {
                block.problem("'range' runs upwards inside Supplementary Private Use Area-A")
            }
            return range
        }

        /** The rules that span more than one entry, once every entry has been read. */
        private fun check(
            file: String,
            fonts: List<Font>,
            problems: MutableList<String>,
        ) {
            val ids = fonts.flatMap { it.ids }
            ids
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .forEach { problems += "$file: font $it is declared twice" }

            val constants = mutableListOf<String>()
            val names = mutableListOf<String>()
            val advances = mutableMapOf<Int, Pair<Int, String>>()
            val sharedRanges = mutableListOf<Pair<IntRange, String>>()
            for (font in fonts) {
                val where = "$file > ${font.id}"
                constants += listOfNotNull(font.constant) + font.spaces.mapNotNull { it.constant } +
                    font.glyphs.mapNotNull { it.constant } + font.lists.map { it.constant }

                font.spaces.groupBy { it.codePoint }.filterValues { it.size > 1 }.keys.forEach {
                    problems += "$where: space U+%X is declared twice".format(it)
                }
                for (space in font.spaces) {
                    val (advance, first) = advances.getOrPut(space.codePoint) { space.advance to font.id }
                    if (advance != space.advance) {
                        problems +=
                            "$where: space U+%X advances %d here and %d in %s, and a space means one width"
                                .format(space.codePoint, space.advance, advance, first)
                    }
                }

                val drawn =
                    font.glyphs.map { it.codePoint } +
                        font.blocks.flatMap { block ->
                            block.chars.orEmpty().flatMap { row -> row.codePoints().toArray().filter { it != 0 } }
                        }
                drawn.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                    problems += "$where: U+%X is drawn twice".format(it)
                }

                val ranged = font.blocks.filter { it.range != null }
                ranged.zipWithNext().forEach { (before, after) ->
                    if (after.range!!.first <= before.range!!.last) {
                        problems += "$where: block ${after.title} does not start after block ${before.title} ends"
                    }
                }
                for (block in ranged) {
                    val range = block.range!!
                    block.glyphs.filter { it.codePoint !in range }.forEach {
                        problems += "$where: U+%X lies outside block %s".format(it.codePoint, block.title)
                    }
                    block.glyphs.zipWithNext().filter { (a, b) -> b.codePoint <= a.codePoint }.forEach { (_, b) ->
                        problems += "$where: U+%X does not follow the glyph before it in block %s".format(b.codePoint, block.title)
                    }
                    if (!font.ownNumbering) {
                        sharedRanges.filter { (other, _) -> other.first <= range.last && range.first <= other.last }.forEach {
                            problems += "$where: block ${block.title} overlaps ${it.second}, and only a font with its own numbering may"
                        }
                        sharedRanges += range to "block ${block.title} of ${font.id}"
                    }
                }

                for (glyph in font.glyphs) {
                    val name = glyph.name
                    if (font.id == DEFAULT_FONT && name == null) {
                        problems += "$where: U+%X has no name, and a message names every glyph of this font".format(glyph.codePoint)
                    }
                    if (font.id != DEFAULT_FONT && name != null) {
                        problems += "$where: U+%X has a name, but only $DEFAULT_FONT names its glyphs".format(glyph.codePoint)
                    }
                    if (name != null) {
                        if (!NAME.matches(name)) problems += "$where: '$name' is not lowercase and hyphenated"
                        names += name
                    }
                }

                val declared = font.lists.map { it.constant }.toSet()
                val used = font.glyphs.mapNotNull { it.list }.toSet()
                (used - declared).forEach { problems += "$where: list $it is used but not declared in 'lists'" }
                (declared - used).forEach { problems += "$where: list $it is declared but no glyph is in it" }
            }
            constants.filterNot { CONSTANT.matches(it) }.forEach { problems += "$file: '$it' is not an upper-case constant" }
            constants
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .forEach { problems += "$file: constant $it is declared twice" }
            names
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .forEach { problems += "$file: name $it is declared twice" }
        }
    }

    /** One JSON object and where it is, for messages that say which entry broke which rule. */
    private class Node(
        value: Any?,
        private val where: String,
        private val problems: MutableList<String>,
        private val parent: String = where,
    ) {
        @Suppress("UNCHECKED_CAST")
        private val map: Map<String, Any?> =
            value as? Map<String, Any?> ?: emptyMap<String, Any?>().also { problem("is not an object") }

        /** The same object, named in messages by [name] instead of its index. */
        fun named(name: String) = Node(map, "$parent > $name", problems, parent)

        fun problem(text: String) {
            problems += "$where: $text"
        }

        fun has(key: String) = key in map

        fun allow(vararg keys: String) {
            (map.keys - keys.toSet()).forEach { problem("unknown key '$it'") }
        }

        fun string(
            key: String,
            required: Boolean = false,
        ): String? =
            when (val value = map[key]) {
                null -> null.also { if (required) problem("'$key' is missing") }
                is String -> value.also { if ("*/" in it) problem("'$key' may not hold */, which ends a doc comment") }
                else -> null.also { problem("'$key' is not a string") }
            }

        fun int(
            key: String,
            required: Boolean = false,
        ): Int? =
            when (val value = map[key]) {
                null -> null.also { if (required) problem("'$key' is missing") }
                is Int -> value
                else -> null.also { problem("'$key' is not a whole number") }
            }

        fun code(key: String): Int? {
            val text = string(key, required = true) ?: return null
            if (!CODE.matches(text)) problem("'$key' is a hex code point in capitals, such as FE004, not '$text'")
            return text.toIntOrNull(16)
        }

        fun strings(key: String): List<String>? =
            when (val value = map[key]) {
                null -> null
                is List<*> -> value.filterIsInstance<String>().also { if (it.size != value.size) problem("'$key' holds a non-string") }
                else -> null.also { problem("'$key' is not a list") }
            }

        fun objects(
            key: String,
            required: Boolean = false,
        ): List<Node> =
            when (val value = map[key]) {
                null -> emptyList<Node>().also { if (required) problem("'$key' is missing") }
                is List<*> -> value.mapIndexed { index, item -> Node(item, "$where > $key[$index]", problems, where) }
                else -> emptyList<Node>().also { problem("'$key' is not a list") }
            }

        fun status(): String? =
            string("status")?.also { if (it !in STATUSES) problem("'status' is one of ${STATUSES.joinToString()}, not '$it'") }
    }
}
