package eu.nordtal.season.build

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * Where the drawn chest window sits against vanilla's, in window pixels: the numbers that move it.
 *
 * Window y 0 is the top of vanilla's window, whose title anchor is (8, 6). A change here takes a build and nothing else,
 * as long as the panel stays inside the [PAD] rows of slack that each texture keeps above and below it.
 */
object ChestPanelGeometry {
    /** The panel's first row, one above the window's top, so the header's margin is 7 as in a dialog. */
    const val TOP = -1

    /** The panel's last row for 0 content rows, vanilla's last. Each content row adds 18, so 6 rows end at 220. */
    const val BOTTOM = 112

    /** The transparent rows above window y 0 and below the bottom in every texture, so [TOP] and [BOTTOM] can move. */
    const val PAD = 8

    /** The ascent that puts the texture's first row [PAD] rows above window y 0, from the title's anchor row. */
    const val ASCENT = 13 + PAD

    /** The window's width, which the panel always spans: x 0 to 175. */
    const val WIDTH = 176

    /** The height of the texture for [rows] content rows: the window and [PAD] rows above and below it. */
    fun height(rows: Int) = PAD + 114 + 18 * rows + PAD
}

/**
 * Draws the chest panels for 1 to 6 rows into [textures] as `assets/nordtal/textures/ui/kit/chest_<rows>.png` and
 * `chest_plain_<rows>.png`, from vanilla's `generic_54.png`: its outline and bevels at the size [ChestPanelGeometry] gives,
 * the player's inventory and hotbar frames at vanilla's places, and in the plain panel a frame on every content slot.
 */
@CacheableTask
abstract class DrawChestPanels : VanillaClientTask() {
    @get:OutputDirectory
    abstract val textures: DirectoryProperty

    init {
        description = "Draws the chest window panels that a chest title carries."
    }

    @TaskAction
    fun draw() {
        val root = textures.get().asFile
        root.deleteRecursively()
        val vanilla =
            ZipFile(jar()).use { zip ->
                val entry = zip.getEntry(GENERIC_54) ?: throw IllegalStateException("the client jar has no $GENERIC_54")
                zip.getInputStream(entry).use { ImageIO.read(it) }
            }
        val directory = root.resolve("assets/nordtal/textures/ui/kit").apply { mkdirs() }
        for (rows in 1..6) {
            for (plain in listOf(false, true)) {
                val name = if (plain) "chest_plain_$rows.png" else "chest_$rows.png"
                ImageIO.write(panel(vanilla, rows, plain), "png", File(directory, name))
            }
        }
    }

    companion object {
        private const val GENERIC_54 = "assets/minecraft/textures/gui/container/generic_54.png"
        private const val EDGE = 5

        /** The panel for [rows] content rows, drawn from [vanilla]'s corners, edges and slot frame. */
        fun panel(
            vanilla: BufferedImage,
            rows: Int,
            plain: Boolean,
        ): BufferedImage {
            val g = ChestPanelGeometry
            val image = BufferedImage(g.WIDTH, g.height(rows), BufferedImage.TYPE_INT_ARGB)
            val top = g.PAD + g.TOP
            val height = g.BOTTOM + 18 * rows - g.TOP + 1
            window(vanilla, image, top, g.WIDTH, height)

            fun slot(
                x: Int,
                y: Int,
            ) = copy(vanilla, 7, 17, image, x, g.PAD + y, 18, 18)
            for (column in 0 until 9) {
                for (row in 0 until 3) slot(7 + 18 * column, 30 + 18 * rows + 18 * row)
                slot(7 + 18 * column, 88 + 18 * rows)
                if (plain) for (row in 0 until rows) slot(7 + 18 * column, 17 + 18 * row)
            }
            return image
        }

        /** Vanilla's window look at any size: generic_54's 5 px corners and edges around its #C6C6C6 fill. */
        private fun window(
            vanilla: BufferedImage,
            image: BufferedImage,
            top: Int,
            width: Int,
            height: Int,
        ) {
            val vw = 176
            val vh = 222
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val sx =
                        if (x < EDGE) {
                            x
                        } else if (x >= width - EDGE) {
                            vw - (width - x)
                        } else {
                            10
                        }
                    val sy =
                        if (y < EDGE) {
                            y
                        } else if (y >= height - EDGE) {
                            vh - (height - y)
                        } else {
                            10
                        }
                    image.setRGB(x, top + y, vanilla.getRGB(sx, sy))
                }
            }
        }

        private fun copy(
            from: BufferedImage,
            fx: Int,
            fy: Int,
            to: BufferedImage,
            tx: Int,
            ty: Int,
            w: Int,
            h: Int,
        ) {
            for (y in 0 until h) for (x in 0 until w) to.setRGB(tx + x, ty + y, from.getRGB(fx + x, fy + y))
        }
    }
}
