package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The distance that each glyph of one font moves the cursor, derived from the font file and its PNGs as the client derives it.
 *
 * The advance of a `space` provider is its number. The advance of a bitmap cell is its rightmost alpha column plus two,
 * scaled by `height / cellHeight`. The first provider that declares a code point wins.
 */
object GlyphAdvances {
    fun of(
        font: File,
        assets: File,
    ): Map<Int, Int> {
        val table = LinkedHashMap<Int, Int>()

        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(font) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        for (provider in root["providers"] as List<Map<String, Any?>>) {
            if (provider["type"] == "space") {
                (provider["advances"] as Map<String, Number>).forEach { (characters, advance) ->
                    characters.codePoints().forEach { table.putIfAbsent(it, advance.toInt()) }
                }
                continue
            }
            if (provider["type"] != "bitmap") continue

            val image = read(texture(assets, provider["file"] as String))
            val rows = (provider["chars"] as List<String>).map { it.codePoints().toArray() }
            val cellWidth = image.width / rows[0].size
            val cellHeight = image.height / rows.size
            val scale = (provider["height"] as Number? ?: 8).toDouble() / cellHeight
            rows.forEachIndexed { row, codePoints ->
                codePoints.forEachIndexed { column, codePoint ->
                    if (codePoint == 0 || table.containsKey(codePoint)) return@forEachIndexed
                    val rightmost = rightmost(image, column * cellWidth, row * cellHeight, cellWidth, cellHeight)
                    table[codePoint] = (0.5 + (rightmost + 1) * scale).toInt() + 1
                }
            }
        }
        return table
    }

    /** Resolves `namespace:path/to.png` against the `assets` of the pack. Without a colon, the namespace is `minecraft`. */
    fun texture(
        assets: File,
        id: String,
    ): File {
        val namespace = if (':' in id) id.substringBefore(':') else "minecraft"
        return assets.resolve(namespace).resolve("textures").resolve(id.substringAfter(':'))
    }

    /** Returns the rightmost column of the cell that has any alpha, counted from the cell, or -1. */
    private fun rightmost(
        image: BufferedImage,
        x0: Int,
        y0: Int,
        width: Int,
        height: Int,
    ): Int {
        for (x in x0 + width - 1 downTo x0) {
            for (y in y0 until y0 + height) {
                if (image.getRGB(x, y) ushr 24 != 0) return x - x0
            }
        }
        return -1
    }

    private fun read(file: File): BufferedImage =
        ImageIO.read(file) ?: throw IllegalStateException("$file is not an image ImageIO can read")
}
