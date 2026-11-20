package eu.nordtal.season.build

import java.awt.image.BufferedImage

/**
 * A vanilla letter sheet whose cells are padded below with transparent rows, so a font can raise its letters.
 *
 * The client refuses a bitmap whose ascent is greater than its height, and a letter drawn higher than vanilla's needs
 * that ascent. A copy whose cell is [height] rows tall, with the letter in its top rows, takes the ascent at scale 1,
 * so every letter keeps its advance.
 */
object RaisedSheet {
    /**
     * `nordtal:font/raised_<height>_<name>` for the vanilla sheet `minecraft:font/<name>`, and
     * `nordtal:font/raised_<height>_<namespace>_<path>` with `/` as `_` for any other texture, such as the pack's own.
     */
    fun id(
        sheet: String,
        height: Int,
    ): String {
        val vanilla = "minecraft:font/"
        val name =
            if (sheet.startsWith(vanilla) && '/' !in sheet.removePrefix(vanilla)) {
                sheet.removePrefix(vanilla)
            } else {
                sheet.replace(':', '_').replace('/', '_')
            }
        return "nordtal:font/raised_${height}_$name"
    }

    /**
     * Pads each of the [rows] cell rows of [image] to [height] rows. The cells must be [cellHeight] tall, the height
     * the font gives them, or the copy would change their scale.
     */
    fun pad(
        image: BufferedImage,
        rows: Int,
        cellHeight: Int,
        height: Int,
    ): BufferedImage {
        check(image.height == rows * cellHeight) {
            "its cells are ${image.height / rows} rows tall but drawn $cellHeight high, so raising it would change its scale"
        }
        check(height >= cellHeight) { "a raised cell of $height rows is shorter than the sheet's $cellHeight" }
        val padded = BufferedImage(image.width, rows * height, BufferedImage.TYPE_INT_ARGB)
        for (row in 0 until rows) {
            for (y in 0 until cellHeight) {
                for (x in 0 until image.width) padded.setRGB(x, row * height + y, image.getRGB(x, row * cellHeight + y))
            }
        }
        return padded
    }
}
