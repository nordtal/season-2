package eu.nordtal.s2.build

import groovy.json.JsonSlurper
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * How far every glyph of one font moves the cursor, derived from the font file and its PNGs the way the client does.
 *
 * A `space` provider's number, or a bitmap cell's rightmost alpha column plus two, scaled by
 * `height / cellHeight`. The first provider to declare a code point wins.
 */
object GlyphAdvances {
    /** @return code point to advance for the font [font], whose textures resolve against [assets] */
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

    /** Resolves `namespace:path/to.png` against the pack's `assets`; no colon means `minecraft`. */
    private fun texture(
        assets: File,
        id: String,
    ): File {
        val namespace = if (':' in id) id.substringBefore(':') else "minecraft"
        return assets.resolve(namespace).resolve("textures").resolve(id.substringAfter(':'))
    }

    /** The rightmost column of the cell with any alpha, relative to the cell, or -1. */
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
