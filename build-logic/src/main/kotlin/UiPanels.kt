package eu.nordtal.season.build

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Dimensions of in-game UI and NPC panels drawn from vanilla's `generic_54.png`. */
object UiPanelGeometry {
    /** One dialog panel size matching `DialogPanel` in `:pack-rendering`. */
    data class DialogSpec(
        val name: String,
        val width: Int,
        val height: Int,
    )

    /** Widest tile a dialog panel texture holds. */
    const val DIALOG_TILE = 256

    /** Ascent of every dialog panel glyph in `nordtal:ui_dialog_{shift}`. */
    const val DIALOG_ASCENT = 20

    /** Ascent of every chest panel glyph in `nordtal:gui`. */
    const val GUI_ASCENT = 13

    /** Width of every chest panel glyph in `nordtal:gui`. */
    const val GUI_WIDTH = VanillaWindow.WIDTH

    /** Height of a `nordtal:gui` chest panel for [rows] content rows. */
    fun guiHeight(rows: Int) = 114 + 18 * rows

    /** Height of every board frame glyph in `nordtal:board`. */
    const val BOARD_CELL = 9

    /** Powers of two tiled by `BoardFrame`. */
    val BOARD_POWERS = listOf(1, 2, 4, 8, 16, 32, 64, 128)

    /** Width of every NPC dialogue panel, matching `DialogueScreen.WIDTH`. */
    const val NPC_PANEL_WIDTH = 300

    /** All 16 NPC dialogue panel heights declared in `glyphs.json` and `DialogueScreen.HEIGHTS`. */
    val NPC_PANEL_HEIGHTS = listOf(48, 51, 56, 58, 61, 66, 68, 70, 71, 76, 78, 80, 81, 88, 90, 100)

    /** Picture side, outer rim, recessed slot well side, frame side and padded texture height of `npc/portrait.png`. */
    const val NPC_PORTRAIT_PICTURE = 36
    const val NPC_PORTRAIT_RIM = 5
    const val NPC_PORTRAIT_WELL = NPC_PORTRAIT_PICTURE + 2
    const val NPC_PORTRAIT_FRAME = NPC_PORTRAIT_WELL + 2 * NPC_PORTRAIT_RIM
    const val NPC_PORTRAIT_HEIGHT = 64

    /** All 18 dialog panel sizes declared in `glyphs.json`. */
    val DIALOGS =
        listOf(
            DialogSpec("ride_list_146", 280, 146),
            DialogSpec("ride_list_42", 280, 42),
            DialogSpec("ride_list_62", 280, 62),
            DialogSpec("ride_list_82", 280, 82),
            DialogSpec("ride_list_102", 280, 102),
            DialogSpec("ride_list_122", 280, 122),
            DialogSpec("ride_list_33", 280, 33),
            DialogSpec("wide_161", 280, 161),
            DialogSpec("compact_116", 200, 116),
            DialogSpec("station_48", 200, 48),
            DialogSpec("station_57", 200, 57),
            DialogSpec("station_66", 200, 66),
            DialogSpec("station_78", 200, 78),
            DialogSpec("station_92", 200, 92),
            DialogSpec("objectives_124", 262, 124),
            DialogSpec("objectives_33", 262, 33),
            DialogSpec("duel_124", 206, 124),
            DialogSpec("wheel_158", 224, 158),
        )
}

/**
 * Draws every vanilla UI and NPC panel texture into [textures] from Minecraft's `generic_54.png`.
 * Produces all 18 dialog panels, the 18 x 18 slot sprite, `ui/gui/panel_1..6` and plain variants,
 * the `ui/board` frame corners and edges, the 16 NPC dialogue panels and `npc/portrait.png`.
 */
@CacheableTask
abstract class DrawUiPanels : VanillaClientTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val npcDefaultBust: RegularFileProperty

    @get:OutputDirectory
    abstract val textures: DirectoryProperty

    init {
        description = "Draws dialog panels, NPC panels and portrait frame, slot sprite, gui panels and board frames."
    }

    @TaskAction
    fun draw() {
        val root = textures.get().asFile
        root.deleteRecursively()
        val vanilla = VanillaWindow.read(jar())
        writeDialogs(vanilla, root.resolve("assets/nordtal/textures/ui/kit").apply { mkdirs() })
        writeGuiPanels(vanilla, root.resolve("assets/nordtal/textures/ui/gui").apply { mkdirs() })
        writeBoardFrame(vanilla, root.resolve("assets/nordtal/textures/ui/board").apply { mkdirs() })
        writeNpcPanels(vanilla, root.resolve("assets/nordtal/textures/npc").apply { mkdirs() })
    }

    private fun writeNpcPanels(
        vanilla: BufferedImage,
        directory: File,
    ) {
        for (height in UiPanelGeometry.NPC_PANEL_HEIGHTS) {
            dialogTiles(vanilla, UiPanelGeometry.NPC_PANEL_WIDTH, height).forEachIndexed { index, tile ->
                ImageIO.write(tile, "png", File(directory, "panel_${height}_$index.png"))
            }
        }
        val bust = ImageIO.read(npcDefaultBust.get().asFile)
        ImageIO.write(npcPortrait(vanilla, bust), "png", File(directory, "portrait.png"))
    }

    private fun writeDialogs(
        vanilla: BufferedImage,
        directory: File,
    ) {
        for (spec in UiPanelGeometry.DIALOGS) {
            dialogTiles(vanilla, spec.width, spec.height).forEachIndexed { index, tile ->
                ImageIO.write(tile, "png", File(directory, "dialog_${spec.name}_$index.png"))
            }
        }
        ImageIO.write(slotSprite(vanilla), "png", File(directory, "slot.png"))
    }

    private fun writeGuiPanels(
        vanilla: BufferedImage,
        directory: File,
    ) {
        for (rows in 1..6) {
            ImageIO.write(guiPanel(vanilla, rows, plain = false), "png", File(directory, "panel_$rows.png"))
            ImageIO.write(guiPanel(vanilla, rows, plain = true), "png", File(directory, "panel_${rows}_plain.png"))
        }
    }

    private fun writeBoardFrame(
        vanilla: BufferedImage,
        directory: File,
    ) {
        ImageIO.write(boardCorner(vanilla, Corner.TOP_LEFT), "png", File(directory, "corner_tl.png"))
        ImageIO.write(boardCorner(vanilla, Corner.TOP_RIGHT), "png", File(directory, "corner_tr.png"))
        ImageIO.write(boardCorner(vanilla, Corner.BOTTOM_LEFT), "png", File(directory, "corner_bl.png"))
        ImageIO.write(boardCorner(vanilla, Corner.BOTTOM_RIGHT), "png", File(directory, "corner_br.png"))
        ImageIO.write(boardVerticalEdge(vanilla, left = true), "png", File(directory, "edge_v_l.png"))
        ImageIO.write(boardVerticalEdge(vanilla, left = false), "png", File(directory, "edge_v_r.png"))
        for (power in UiPanelGeometry.BOARD_POWERS) {
            ImageIO.write(boardHorizontalEdge(vanilla, power, top = true), "png", File(directory, "edge_h_$power.png"))
            ImageIO.write(boardHorizontalEdge(vanilla, power, top = false), "png", File(directory, "edge_b_$power.png"))
        }
    }

    enum class Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
    }

    companion object {
        /** Slices a [width] by [height] vanilla window into tiles of at most [UiPanelGeometry.DIALOG_TILE] columns. */
        fun dialogTiles(
            vanilla: BufferedImage,
            width: Int,
            height: Int,
        ): List<BufferedImage> {
            val full = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            VanillaWindow.window(vanilla, full, 0, width, height)
            val tiles = mutableListOf<BufferedImage>()
            var offset = 0
            while (offset < width) {
                val tileWidth = minOf(UiPanelGeometry.DIALOG_TILE, width - offset)
                val tile = BufferedImage(tileWidth, height, BufferedImage.TYPE_INT_ARGB)
                VanillaWindow.copy(full, offset, 0, tile, 0, 0, tileWidth, height)
                tiles += tile
                offset += tileWidth
            }
            return tiles
        }

        /** Draws the 48 x 64 NPC avatar frame from [vanilla] with a 5 px rim, 38 x 38 slot well and 36 x 36 [picture]. */
        fun npcPortrait(
            vanilla: BufferedImage,
            picture: BufferedImage? = null,
        ): BufferedImage {
            val frame =
                BufferedImage(
                    UiPanelGeometry.NPC_PORTRAIT_FRAME,
                    UiPanelGeometry.NPC_PORTRAIT_HEIGHT,
                    BufferedImage.TYPE_INT_ARGB,
                )
            val rim = UiPanelGeometry.NPC_PORTRAIT_RIM
            val well = UiPanelGeometry.NPC_PORTRAIT_WELL
            val pictureOffset = rim + 1
            VanillaWindow.window(vanilla, frame, 0, UiPanelGeometry.NPC_PORTRAIT_FRAME, UiPanelGeometry.NPC_PORTRAIT_FRAME)
            VanillaWindow.slotWell(vanilla, frame, rim, rim, well, well)
            if (picture != null) {
                for (y in 0 until minOf(picture.height, UiPanelGeometry.NPC_PORTRAIT_PICTURE)) {
                    for (x in 0 until minOf(picture.width, UiPanelGeometry.NPC_PORTRAIT_PICTURE)) {
                        val argb = picture.getRGB(x, y)
                        val alpha = argb ushr 24
                        if (alpha == 255) {
                            frame.setRGB(pictureOffset + x, pictureOffset + y, argb)
                        } else if (alpha > 0) {
                            frame.setRGB(
                                pictureOffset + x,
                                pictureOffset + y,
                                sourceOver(frame.getRGB(pictureOffset + x, pictureOffset + y), argb),
                            )
                        }
                    }
                }
            }
            return frame
        }

        private fun sourceOver(
            below: Int,
            top: Int,
        ): Int {
            val a = (top ushr 24) / 255.0
            val b = (below ushr 24) / 255.0 * (1.0 - a)
            val out = a + b
            if (out == 0.0) return 0
            var argb = Math.round(out * 255.0).toInt() shl 24
            for (shift in intArrayOf(0, 8, 16)) {
                val c = ((top ushr shift and 0xFF) * a + (below ushr shift and 0xFF) * b) / out
                argb = argb or (Math.round(c).toInt() shl shift)
            }
            return argb
        }

        /** Extracts vanilla's 18 x 18 slot frame from [vanilla]. */
        fun slotSprite(vanilla: BufferedImage): BufferedImage {
            val image = BufferedImage(VanillaWindow.SLOT_SIZE, VanillaWindow.SLOT_SIZE, BufferedImage.TYPE_INT_ARGB)
            VanillaWindow.slot(vanilla, image, 0, 0)
            return image
        }

        /** Draws a `nordtal:gui` chest panel for [rows] content rows, with content slots unless [plain]. */
        fun guiPanel(
            vanilla: BufferedImage,
            rows: Int,
            plain: Boolean,
        ): BufferedImage {
            val height = UiPanelGeometry.guiHeight(rows)
            val image = BufferedImage(UiPanelGeometry.GUI_WIDTH, height, BufferedImage.TYPE_INT_ARGB)
            VanillaWindow.window(vanilla, image, 0, UiPanelGeometry.GUI_WIDTH, height)
            for (column in 0 until 9) {
                for (row in 0 until 3) VanillaWindow.slot(vanilla, image, 7 + 18 * column, 30 + 18 * rows + 18 * row)
                VanillaWindow.slot(vanilla, image, 7 + 18 * column, 88 + 18 * rows)
                if (!plain) for (row in 0 until rows) VanillaWindow.slot(vanilla, image, 7 + 18 * column, 17 + 18 * row)
            }
            return image
        }

        /** Draws one 9 x 9 board corner from [vanilla]'s 6 x 6 corner bevel, leaving the interior fill transparent. */
        fun boardCorner(
            vanilla: BufferedImage,
            corner: Corner,
        ): BufferedImage {
            val cell = UiPanelGeometry.BOARD_CELL
            val image = BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB)
            val fill = vanilla.getRGB(10, 10)
            val srcX0 = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) 0 else VanillaWindow.WIDTH - 6
            val srcY0 = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) 0 else VanillaWindow.HEIGHT - 6
            val dstX0 = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) 3 else 0
            val dstY0 = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) 3 else 0
            for (dy in 0 until 6) {
                for (dx in 0 until 6) {
                    val sx = srcX0 + dx
                    val sy = srcY0 + dy
                    val rgb = vanilla.getRGB(sx, sy)
                    val innerX = if (srcX0 == 0) dx >= 3 else dx <= 2
                    val innerY = if (srcY0 == 0) dy >= 3 else dy <= 2
                    if (innerX && innerY && rgb == fill) continue
                    image.setRGB(dstX0 + dx, dstY0 + dy, rgb)
                }
            }
            return image
        }

        /** Draws one 9 x 9 vertical board edge from [vanilla]'s 3 px left or right bevel at columns 3..5. */
        fun boardVerticalEdge(
            vanilla: BufferedImage,
            left: Boolean,
        ): BufferedImage {
            val cell = UiPanelGeometry.BOARD_CELL
            val image = BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB)
            val srcX0 = if (left) 0 else VanillaWindow.WIDTH - 3
            for (y in 0 until cell) {
                for (dx in 0 until 3) {
                    image.setRGB(3 + dx, y, vanilla.getRGB(srcX0 + dx, 10))
                }
            }
            return image
        }

        /** Draws one [width] by 9 horizontal board edge from [vanilla]'s 3 px top or bottom bevel at rows 3..5. */
        fun boardHorizontalEdge(
            vanilla: BufferedImage,
            width: Int,
            top: Boolean,
        ): BufferedImage {
            val image = BufferedImage(width, UiPanelGeometry.BOARD_CELL, BufferedImage.TYPE_INT_ARGB)
            val srcY0 = if (top) 0 else VanillaWindow.HEIGHT - 3
            for (dy in 0 until 3) {
                val rgb = vanilla.getRGB(10, srcY0 + dy)
                for (x in 0 until width) {
                    image.setRGB(x, 3 + dy, rgb)
                }
            }
            return image
        }
    }
}
