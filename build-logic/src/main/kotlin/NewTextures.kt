package eu.nordtal.season.build

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Creates each kind of texture in the resource pack at [pack]: a glyph, a glyph block, a sheet and an item.
 *
 * Each function writes a transparent PNG and the JSON entries that its kind needs, and returns the files it wrote. It
 * refuses a file that exists, and checks `glyphs.json` with [GlyphAllocation] before it writes anything.
 */
object NewTextures {
    private const val FIRST = 0xFE000
    private const val LAST = 0xFFFFD

    /**
     * Adds a glyph at the next free code point of [block], a provisional one until a publish assigns the final one.
     *
     * The display height is the pixel [height]. [ascent] falls back to the block's.
     */
    fun glyph(
        pack: File,
        font: String,
        block: String,
        texture: String,
        constant: String?,
        name: String?,
        width: Int,
        height: Int,
        ascent: Int? = null,
    ): List<File> {
        val png = png(pack, texture)
        return edit(pack, png, width, height) { root ->
            val target = block(font(root, font), font, block)
            require(target["texture"] == null && target["chars"] == null) {
                "Block $block of $font is a sheet, whose cells are drawn in its one texture."
            }
            val range = range(target)
            val glyphs = target.getOrPut("glyphs") { OxfmtJson.Array() } as MutableList<Any?>
            val used = glyphs.mapNotNull { ((it as Map<*, *>)["code"] as? String)?.toIntOrNull(16) }
            val code = (used.maxOrNull()?.plus(1) ?: range.first).takeIf { it in range }
            requireNotNull(code) { "Block $block of $font has no free code point left in ${target["range"]}." }
            val entry = OxfmtJson.Object()
            entry["code"] = "%X".format(code)
            entry["constant"] =
                constant ?: name?.uppercase()?.replace('-', '_') ?: throw IllegalArgumentException("A glyph needs a constant or a name.")
            name?.let { entry["name"] = it }
            entry["texture"] = texture
            if (target["height"] != height) entry["height"] = height
            val ascentOf =
                ascent ?: target["ascent"] as Int?
                    ?: throw IllegalArgumentException("Block $block of $font sets no ascent, so the glyph needs one.")
            if (target["ascent"] != ascentOf) entry["ascent"] = ascentOf
            glyphs += entry
        }
    }

    /** Adds an empty block of [size] code points at the first free run of the numbering that [font] shares, or at [range]. */
    fun block(
        pack: File,
        font: String,
        title: String,
        range: String? = null,
        size: Int = 16,
        height: Int? = null,
        ascent: Int? = null,
    ): List<File> =
        edit(pack, null, 0, 0) { root ->
            val block = newBlock(root, font, title, range, size)
            height?.let { block["height"] = it }
            ascent?.let { block["ascent"] = it }
            block["glyphs"] = OxfmtJson.Array()
        }

    /** Adds a sheet whose [constants] are the cells of one row of [texture], [width] by [height] pixels each. */
    fun sheet(
        pack: File,
        font: String,
        title: String,
        texture: String,
        constants: List<String>,
        width: Int,
        height: Int,
        ascent: Int,
        range: String? = null,
    ): List<File> {
        require(constants.isNotEmpty()) { "A sheet needs at least one cell." }
        val png = png(pack, texture)
        return edit(pack, png, width * constants.size, height) { root ->
            val block = newBlock(root, font, title, range, maxOf(16, constants.size))
            val first = range(block).first
            block["texture"] = texture
            block["height"] = height
            block["ascent"] = ascent
            block["glyphs"] =
                OxfmtJson.Array().apply {
                    constants.forEachIndexed { index, constant ->
                        add(
                            linkedMapOf(
                                "code" to "%X".format(first + index),
                                "constant" to constant,
                            ),
                        )
                    }
                }
        }
    }

    /** Adds the item [id], such as `nordtal:wand`: its definition, a `minecraft:item/generated` model and its PNG. */
    fun item(
        pack: File,
        id: String,
        width: Int,
        height: Int,
    ): List<File> {
        val (namespace, path) = Identifier.parse(id)
        val assets = pack.resolve("src/assets").resolve(namespace)
        val files =
            listOf(assets.resolve("items/$path.json"), assets.resolve("models/item/$path.json"), assets.resolve("textures/item/$path.png"))
        files.forEach { refuseExisting(pack, it) }
        checkSize(width, height)
        val model = "$namespace:item/$path"
        files.forEach { it.parentFile.mkdirs() }
        OxfmtJson.write(files[0], mapOf("model" to mapOf("type" to "minecraft:model", "model" to model)))
        OxfmtJson.write(files[1], mapOf("parent" to "minecraft:item/generated", "textures" to mapOf("layer0" to model)))
        transparent(files[2], width, height)
        return files
    }

    /** Applies [change] to `glyphs.json`, checks the result, and writes it and a transparent [png] of [width] by [height]. */
    private fun edit(
        pack: File,
        png: File?,
        width: Int,
        height: Int,
        change: (OxfmtJson.Object) -> Unit,
    ): List<File> {
        png?.let { checkSize(width, height) }
        val allocation = pack.resolve("glyphs.json")
        val root = OxfmtJson.read(allocation) as OxfmtJson.Object
        change(root)
        val text = OxfmtJson.format(root)
        val draft = Files.createTempDirectory("glyphs").toFile()
        try {
            draft.resolve(allocation.name).writeText(text)
            GlyphAllocation.read(draft.resolve(allocation.name))
        } finally {
            draft.deleteRecursively()
        }
        allocation.writeText(text)
        png?.let { transparent(it, width, height) }
        return listOfNotNull(allocation, png)
    }

    private fun newBlock(
        root: OxfmtJson.Object,
        font: String,
        title: String,
        range: String?,
        size: Int,
    ): OxfmtJson.Object {
        val entry = font(root, font)
        val blocks = entry["blocks"] as MutableList<Any?>
        require(blocks.none { (it as Map<*, *>)["title"] == title }) { "The font $font has a block $title already." }
        val run = range?.let { parseRange(it) } ?: freeRun(root, entry, size)
        val block = OxfmtJson.Object()
        block["range"] = "%X-%X".format(run.first, run.last)
        block["title"] = title
        val index =
            blocks.indexOfFirst { other ->
                (other as Map<*, *>)["range"]?.let { parseRange(it as String).first > run.first } == true
            }
        blocks.add(if (index < 0) blocks.size else index, block)
        return block
    }

    /** The first run of [size] code points, aligned to [size], that no block of a font sharing the numbering of [font] and no space of [font] uses. */
    private fun freeRun(
        root: Map<*, *>,
        font: Map<*, *>,
        size: Int,
    ): IntRange {
        val sharing = if (font["numbering"] == "own") listOf(font) else fonts(root).filter { it === font || it["numbering"] != "own" }
        val taken =
            sharing.flatMap { f -> (f["blocks"] as List<*>).mapNotNull { (it as Map<*, *>)["range"] as String? }.map { parseRange(it) } } +
                (font["spaces"] as List<*>? ?: emptyList<Any>()).map {
                    ((it as Map<*, *>)["code"] as String).toInt(16).let { code ->
                        code..code
                    }
                }
        return generateSequence(FIRST) { it + size }
            .takeWhile { it + size - 1 <= LAST }
            .map { it until it + size }
            .firstOrNull { run -> taken.none { it.first <= run.last && run.first <= it.last } }
            ?: throw IllegalArgumentException("No run of $size free code points is left.")
    }

    private fun fonts(root: Map<*, *>): List<Map<*, *>> = (root["fonts"] as List<*>).map { it as Map<*, *> }

    private fun font(
        root: Map<*, *>,
        font: String,
    ): OxfmtJson.Object =
        fonts(root).firstOrNull { it["font"] == font } as OxfmtJson.Object?
            ?: throw IllegalArgumentException("glyphs.json has no font $font.")

    private fun block(
        font: Map<*, *>,
        id: String,
        title: String,
    ): OxfmtJson.Object =
        (font["blocks"] as List<*>).firstOrNull { (it as Map<*, *>)["title"] == title } as OxfmtJson.Object?
            ?: throw IllegalArgumentException("The font $id has no block $title.")

    private fun range(block: Map<*, *>): IntRange = parseRange(block["range"] as String)

    private fun parseRange(text: String): IntRange {
        val bounds = text.split('-').map { it.toIntOrNull(16) }
        require(bounds.size == 2 && bounds.all { it != null }) { "$text is not a range such as FE000-FE00F." }
        return bounds[0]!!..bounds[1]!!
    }

    /** The file of the texture [id] under `src/assets`, refused when it exists. */
    private fun png(
        pack: File,
        id: String,
    ): File {
        val (namespace, path) = Identifier.parse(id, ".png")
        return pack.resolve("src/assets/$namespace/textures/$path").also { refuseExisting(pack, it) }
    }

    private fun refuseExisting(
        pack: File,
        file: File,
    ) = check(!file.exists()) { "${file.relativeTo(pack).invariantSeparatorsPath} exists already." }

    private fun transparent(
        file: File,
        width: Int,
        height: Int,
    ) {
        checkSize(width, height)
        file.parentFile.mkdirs()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
    }

    private fun checkSize(
        width: Int,
        height: Int,
    ) = require(width in 1..4096 && height in 1..4096) { "A texture is 1 to 4096 pixels wide and high, not $width by $height." }
}
