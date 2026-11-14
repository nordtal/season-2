package eu.nordtal.season.build

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Creates, edits and deletes the fonts, blocks and sheets of the pack at [pack], and adds cells to a sheet. A block or sheet
 * is found by its font and its title. Every change goes through [NewTextures.edit], so `glyphs.json` keeps its layout and
 * rules.
 */
object FontEdits {
    private val FONT_KEYS = listOf("font", "rows", "pitch", "constant", "numbering", "about", "spaces", "blocks", "lists")
    private val BLOCK_KEYS = listOf("range", "title", "about", "texture", "height", "ascent", "status", "chars", "glyphs")

    /** Adds the font [font], such as `nordtal:signs`, with its Java [constant], after every other font and without blocks. */
    fun createFont(
        pack: File,
        font: String,
        constant: String,
        about: String? = null,
    ): List<File> {
        val (namespace, _) = Identifier.parse(font)
        if (namespace == "minecraft") {
            throw FieldRefusal("namespace", "A font of minecraft is one of the client's: give it a namespace of its own.")
        }
        if (constant.isBlank()) throw FieldRefusal("constant", "A font needs the constant by which code names it.")
        return NewTextures.edit(pack, null, 0, 0, refused = "constant") { root ->
            val fonts = root["fonts"] as MutableList<Any?>
            if (fonts.any { (it as Map<*, *>)["font"] == font }) throw FieldRefusal("path", "glyphs.json has a font $font already.")
            val entry = OxfmtJson.Object()
            entry["font"] = font
            entry["constant"] = constant
            about?.takeIf { it.isNotEmpty() }?.let { entry["about"] = it }
            entry["blocks"] = OxfmtJson.Array()
            fonts += entry
        }
    }

    /**
     * Sets the [constant] and [about] of [font] that are not null. An empty about removes it. A new constant is refused while
     * code uses the old one.
     */
    fun editFont(
        pack: File,
        repository: File,
        font: String,
        constant: String? = null,
        about: String? = null,
    ): List<File> {
        val old = NewTextures.font(OxfmtJson.read(pack.resolve("glyphs.json")) as Map<*, *>, font)["constant"] as String?
        if (constant != null && constant != old) {
            if (font == GlyphAllocation.DEFAULT_FONT) {
                throw FieldRefusal("constant", "$font is the font of ordinary text, which code names without a constant.")
            }
            if (constant.isBlank()) throw FieldRefusal("constant", "A font needs the constant by which code names it.")
            old?.let { GlyphEdits.refuseUsed(repository, "The constant $it of $font", listOf(it), emptyList()) }
        }
        return NewTextures.edit(pack, null, 0, 0) { root ->
            val entry = NewTextures.font(root, font)
            constant?.let { entry["constant"] = it }
            about?.let { if (it.isEmpty()) entry.remove("about") else entry["about"] = it }
            order(entry, FONT_KEYS)
        }
    }

    /**
     * Sets the [title], [status], display [height], [ascent] and [about] of the block or sheet [block] of [font] that are not
     * null. An empty status or about removes it.
     */
    fun editBlock(
        pack: File,
        font: String,
        block: String,
        title: String? = null,
        status: String? = null,
        height: Int? = null,
        ascent: Int? = null,
        about: String? = null,
    ): List<File> =
        NewTextures.edit(pack, null, 0, 0) { root ->
            val entry = block(root, font, block)
            if (title != null && title != block) {
                if (title.isBlank()) throw FieldRefusal("title", "A block needs a title.")
                if ((NewTextures.font(root, font)["blocks"] as List<*>?).orEmpty().any { (it as Map<*, *>)["title"] == title }) {
                    throw FieldRefusal("title", "The font $font has a block $title already.")
                }
                entry["title"] = title
            }
            height?.let { entry["height"] = it }
            ascent?.let { entry["ascent"] = it }
            for ((key, value) in listOf("status" to status, "about" to about)) {
                if (value == null) continue
                if (value.isEmpty()) entry.remove(key) else entry[key] = value
            }
            order(entry, BLOCK_KEYS)
        }

    /**
     * Adds one cell per entry of [constants] after the last cell of the sheet [block] of [font], and widens its PNG by one
     * cell per new cell. The new columns are transparent.
     */
    fun addCells(
        pack: File,
        font: String,
        block: String,
        constants: List<String>,
    ): List<File> {
        if (constants.isEmpty() || constants.any { it.isBlank() }) throw FieldRefusal("constants", "Name one constant per new cell.")
        val sheet = block(OxfmtJson.read(pack.resolve("glyphs.json")) as Map<*, *>, font, block)
        val texture = sheet["texture"] as String? ?: throw FieldRefusal("block", "Block $block of $font is no sheet.")
        if (sheet["chars"] != null) {
            throw FieldRefusal("block", "The sheet $block of $font holds ordinary characters, which code writes as text.")
        }
        val cells = sheet["glyphs"] as List<*>
        val png = GlyphEdits.sources(pack, texture)[0]
        val image =
            png.takeIf { it.isFile }?.let { ImageIO.read(it) }
                ?: throw IllegalArgumentException("${png.relativeTo(pack).invariantSeparatorsPath} is no PNG that can be read.")
        if (cells.isEmpty() || image.width % cells.size != 0) {
            throw IllegalArgumentException(
                "${png.relativeTo(pack).invariantSeparatorsPath} is ${image.width} pixels wide, which is no whole number of " +
                    "${cells.size} cells.",
            )
        }
        val width = image.width + image.width / cells.size * constants.size
        if (width > 4096) throw FieldRefusal("constants", "The sheet would be $width pixels wide, and a texture is at most 4096.")
        val wider = BufferedImage(width, image.height, BufferedImage.TYPE_INT_ARGB)
        wider.createGraphics().apply {
            drawImage(image, 0, 0, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(wider, "png", it) }.toByteArray()
        return NewTextures.edit(pack, null, 0, 0, refused = "constants", write = mapOf(png to bytes)) { root ->
            val target = block(root, font, block)
            val glyphs = target["glyphs"] as MutableList<Any?>
            val last = ((glyphs.last() as Map<*, *>)["code"] as String).toInt(16)
            constants.forEachIndexed { index, constant ->
                val code = last + 1 + index
                if (code !in NewTextures.range(target)) {
                    throw FieldRefusal("constants", "The sheet $block of $font has no free code point left in ${target["range"]}.")
                }
                glyphs +=
                    linkedMapOf("code" to "%X".format(code), "title" to NewTextures.titleOf(constant.trim()), "constant" to constant.trim())
            }
        }
    }

    /**
     * Removes the block or sheet [block] of [font], and the PNG and `.aseprite` source of each of its textures that nothing
     * else draws. Refused while code uses one of its constants or names, or a list that would lose its last glyph.
     */
    fun deleteBlock(
        pack: File,
        repository: File,
        font: String,
        block: String,
    ): List<File> {
        val root = OxfmtJson.read(pack.resolve("glyphs.json")) as Map<*, *>
        val entry = block(root, font, block)
        if (entry["chars"] != null) {
            throw IllegalArgumentException("The sheet $block of $font holds ordinary characters, which code writes as text, so it stays.")
        }
        val fontEntry = NewTextures.font(root, font)
        refuse(repository, "Block $block of $font", fontEntry, listOf(entry), emptyList())
        return NewTextures.edit(pack, null, 0, 0, removals(pack, root, listOf(entry))) { edited ->
            val target = NewTextures.font(edited, font)
            val blocks = target["blocks"] as MutableList<*>
            blocks.removeAt(blocks.indexOfFirst { (it as Map<*, *>)["title"] == block })
            dropEmptyLists(target)
        }
    }

    /**
     * Removes [font] with every block and sheet, and the PNG and `.aseprite` source of each of its textures that nothing else
     * draws. Refused for the font of ordinary text, and while code uses its id, its constant or one of its glyphs.
     */
    fun deleteFont(
        pack: File,
        repository: File,
        font: String,
    ): List<File> {
        if (font == GlyphAllocation.DEFAULT_FONT) {
            throw IllegalArgumentException("$font is the font of ordinary text, which every message draws, so it stays.")
        }
        val root = OxfmtJson.read(pack.resolve("glyphs.json")) as Map<*, *>
        val entry = NewTextures.font(root, font)
        val blocks = (entry["blocks"] as List<*>?).orEmpty().map { it as Map<*, *> }
        val ids = (entry["rows"] as Int?)?.let { rows -> (0 until rows).map { font.replace("{row}", "$it") } } ?: listOf(font)
        val own =
            listOfNotNull(entry["constant"] as String?) +
                (entry["spaces"] as List<*>?).orEmpty().mapNotNull { (it as Map<*, *>)["constant"] as String? } +
                (entry["lists"] as List<*>?).orEmpty().mapNotNull { (it as Map<*, *>)["constant"] as String? }
        refuse(repository, "The font $font", entry, blocks, own, ids)
        return NewTextures.edit(pack, null, 0, 0, removals(pack, root, blocks)) { edited ->
            val fonts = edited["fonts"] as MutableList<*>
            fonts.removeAt(fonts.indexOfFirst { (it as Map<*, *>)["font"] == font })
        }
    }

    /**
     * Refuses while code uses a constant or name of the glyphs of [blocks] in [font], a list that would lose its last glyph,
     * one of [constants] or one of the font [ids].
     */
    private fun refuse(
        repository: File,
        label: String,
        font: Map<*, *>,
        blocks: List<Map<*, *>>,
        constants: List<String>,
        ids: List<String> = emptyList(),
    ) {
        val glyphs = blocks.flatMap { (it["glyphs"] as List<*>?).orEmpty() }.map { it as Map<*, *> }
        val kept =
            (font["blocks"] as List<*>?)
                .orEmpty()
                .filter { block -> blocks.none { it === block } }
                .flatMap { ((it as Map<*, *>)["glyphs"] as List<*>?).orEmpty() }
                .mapNotNull { (it as Map<*, *>)["list"] }
                .toSet()
        val lists = glyphs.mapNotNull { it["list"] as String? }.filter { it !in kept }
        GlyphEdits.refuseUsed(
            repository,
            label,
            constants + glyphs.mapNotNull { it["constant"] as String? } + lists.distinct(),
            glyphs.mapNotNull { it["name"] as String? },
            ids,
        )
    }

    /** The sources of the textures that only [blocks] draw, among the glyphs of [root] and the pack's models. */
    private fun removals(
        pack: File,
        root: Map<*, *>,
        blocks: List<Map<*, *>>,
    ): List<File> {
        val gone =
            blocks.flatMap { block ->
                listOfNotNull(block["texture"] as String?) +
                    (block["glyphs"] as List<*>?).orEmpty().mapNotNull { (it as Map<*, *>)["texture"] as String? }
            }
        val counts = GlyphEdits.textures(root).groupingBy { it }.eachCount()
        val models = GlyphEdits.modelTextures(pack)
        return gone
            .distinct()
            .filter { texture -> counts[texture] == gone.count { it == texture } && texture.removeSuffix(".png") !in models }
            .flatMap { GlyphEdits.sources(pack, it) }
            .filter { it.exists() }
    }

    /** Removes each list of [font] that no glyph joins any more. */
    private fun dropEmptyLists(font: OxfmtJson.Object) {
        val lists = font["lists"] as MutableList<*>? ?: return
        val used =
            (font["blocks"] as List<*>?)
                .orEmpty()
                .flatMap { ((it as Map<*, *>)["glyphs"] as List<*>?).orEmpty() }
                .mapNotNull { (it as Map<*, *>)["list"] }
                .toSet()
        lists.removeAll { (it as Map<*, *>)["constant"] !in used }
        if (lists.isEmpty()) font.remove("lists")
    }

    private fun block(
        root: Map<*, *>,
        font: String,
        title: String,
    ): OxfmtJson.Object {
        val blocks = (NewTextures.font(root, font)["blocks"] as List<*>?).orEmpty()
        return blocks.firstOrNull { (it as Map<*, *>)["title"] == title } as OxfmtJson.Object?
            ?: throw FieldRefusal("block", "The font $font has no block $title.")
    }

    /** Puts the keys of [entry] in the order of [keys], as `glyphs.json` writes them. */
    private fun order(
        entry: OxfmtJson.Object,
        keys: List<String>,
    ) {
        val entries = entry.entries.sortedBy { keys.indexOf(it.key).takeIf { index -> index >= 0 } ?: keys.size }.map { it.toPair() }
        entry.clear()
        entry.putAll(entries)
    }
}
