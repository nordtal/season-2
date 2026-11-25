package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File

class UiPanelsTest {
    @Test
    fun glyphsJsonDeclaresAllEighteenDialogPanelsAtTheSizesTheBuildDraws() {
        val root = File(System.getProperty("nordtal.resourcePack"))
        val dialog = GlyphAllocation.read(root.resolve("glyphs.json")).fonts.single { it.id == "nordtal:ui_dialog_{shift}" }
        val glyphs = dialog.blocks.flatMap { it.glyphs }
        val expectedTiles =
            UiPanelGeometry.DIALOGS
                .flatMap { spec ->
                    val count = (spec.width + UiPanelGeometry.DIALOG_TILE - 1) / UiPanelGeometry.DIALOG_TILE
                    (0 until count).map { index -> "nordtal:ui/kit/dialog_${spec.name}_$index.png" to spec.height }
                }.toMap()
        assertEquals(expectedTiles.size, glyphs.size)
        for (glyph in glyphs) {
            assertEquals(expectedTiles[glyph.texture], glyph.height, glyph.texture)
            assertEquals(UiPanelGeometry.DIALOG_ASCENT, glyph.ascent, glyph.texture)
        }
    }

    @Test
    fun glyphsJsonDeclaresGuiPanelsAtVanillaChestHeights() {
        val root = File(System.getProperty("nordtal.resourcePack"))
        val gui = GlyphAllocation.read(root.resolve("glyphs.json")).fonts.single { it.id == "nordtal:gui" }
        val panels = gui.blocks.flatMap { it.glyphs }.filter { it.texture.startsWith("nordtal:ui/gui/panel_") }
        assertEquals(12, panels.size)
        for (glyph in panels) {
            val rows = Regex("panel_([1-6])(_plain)?\\.png").find(glyph.texture)!!.groupValues[1].toInt()
            assertEquals(UiPanelGeometry.guiHeight(rows), glyph.height, glyph.texture)
            assertEquals(UiPanelGeometry.GUI_ASCENT, glyph.ascent, glyph.texture)
        }
    }

    @Test
    fun dialogTilesMatchGeneric54CornersEdgesAndFillPixelForPixel() {
        val vanilla = syntheticGeneric54()
        for (spec in UiPanelGeometry.DIALOGS) {
            val tiles = DrawUiPanels.dialogTiles(vanilla, spec.width, spec.height)
            assertEquals(spec.width, tiles.sumOf { it.width }, spec.name)
            tiles.forEach { tile ->
                assertEquals(spec.height, tile.height, spec.name)
                assertTrue(tile.width <= UiPanelGeometry.DIALOG_TILE, spec.name)
            }
            val first = tiles.first()
            val last = tiles.last()
            for (y in 0 until VanillaWindow.EDGE) {
                for (x in 0 until VanillaWindow.EDGE) {
                    assertEquals(vanilla.getRGB(x, y), first.getRGB(x, y), "${spec.name} TL ($x,$y)")
                    assertEquals(
                        vanilla.getRGB(VanillaWindow.WIDTH - VanillaWindow.EDGE + x, y),
                        last.getRGB(last.width - VanillaWindow.EDGE + x, y),
                        "${spec.name} TR ($x,$y)",
                    )
                    assertEquals(
                        vanilla.getRGB(x, VanillaWindow.HEIGHT - VanillaWindow.EDGE + y),
                        first.getRGB(x, spec.height - VanillaWindow.EDGE + y),
                        "${spec.name} BL ($x,$y)",
                    )
                    assertEquals(
                        vanilla.getRGB(VanillaWindow.WIDTH - VanillaWindow.EDGE + x, VanillaWindow.HEIGHT - VanillaWindow.EDGE + y),
                        last.getRGB(last.width - VanillaWindow.EDGE + x, spec.height - VanillaWindow.EDGE + y),
                        "${spec.name} BR ($x,$y)",
                    )
                }
            }
        }
    }

    @Test
    fun guiPanelsUseVanillaCornersAndPlaceContentSlotsOnlyOnNonPlainVariants() {
        val vanilla = syntheticGeneric54()
        val fill = vanilla.getRGB(10, 10)
        val slotBorder = vanilla.getRGB(VanillaWindow.SLOT_X, VanillaWindow.SLOT_Y)
        for (rows in 1..6) {
            val framed = DrawUiPanels.guiPanel(vanilla, rows, plain = false)
            val plain = DrawUiPanels.guiPanel(vanilla, rows, plain = true)
            assertEquals(UiPanelGeometry.GUI_WIDTH, framed.width)
            assertEquals(UiPanelGeometry.guiHeight(rows), framed.height)
            assertEquals(slotBorder, framed.getRGB(7, 17), "panel_$rows content slot")
            assertEquals(fill, plain.getRGB(7, 17), "panel_${rows}_plain has no content slot")
            assertEquals(slotBorder, plain.getRGB(7, 30 + 18 * rows), "panel_${rows}_plain keeps inventory slot")
            assertEquals(slotBorder, plain.getRGB(7, 88 + 18 * rows), "panel_${rows}_plain keeps hotbar slot")
        }
    }

    @Test
    fun npcPanelsAndPortraitMatchGeneric54CornersEdgesAndSlotWell() {
        val vanilla = syntheticGeneric54()
        for (height in UiPanelGeometry.NPC_PANEL_HEIGHTS) {
            val tiles = DrawUiPanels.dialogTiles(vanilla, UiPanelGeometry.NPC_PANEL_WIDTH, height)
            assertEquals(2, tiles.size, "height $height splits into two tiles")
            assertEquals(256, tiles[0].width)
            assertEquals(44, tiles[1].width)
            for (y in 0 until VanillaWindow.EDGE) {
                for (x in 0 until VanillaWindow.EDGE) {
                    assertEquals(vanilla.getRGB(x, y), tiles[0].getRGB(x, y), "panel_$height TL ($x,$y)")
                    assertEquals(
                        vanilla.getRGB(VanillaWindow.WIDTH - VanillaWindow.EDGE + x, y),
                        tiles[1].getRGB(tiles[1].width - VanillaWindow.EDGE + x, y),
                        "panel_$height TR ($x,$y)",
                    )
                    assertEquals(
                        vanilla.getRGB(x, VanillaWindow.HEIGHT - VanillaWindow.EDGE + y),
                        tiles[0].getRGB(x, height - VanillaWindow.EDGE + y),
                        "panel_$height BL ($x,$y)",
                    )
                    assertEquals(
                        vanilla.getRGB(VanillaWindow.WIDTH - VanillaWindow.EDGE + x, VanillaWindow.HEIGHT - VanillaWindow.EDGE + y),
                        tiles[1].getRGB(tiles[1].width - VanillaWindow.EDGE + x, height - VanillaWindow.EDGE + y),
                        "panel_$height BR ($x,$y)",
                    )
                }
            }
        }
        val portrait = DrawUiPanels.npcPortrait(vanilla)
        assertEquals(UiPanelGeometry.NPC_PORTRAIT_FRAME, portrait.width)
        assertEquals(UiPanelGeometry.NPC_PORTRAIT_HEIGHT, portrait.height)
        for (y in 0 until VanillaWindow.EDGE) {
            for (x in 0 until VanillaWindow.EDGE) {
                assertEquals(vanilla.getRGB(x, y), portrait.getRGB(x, y), "portrait TL ($x,$y)")
                assertEquals(
                    vanilla.getRGB(VanillaWindow.WIDTH - VanillaWindow.EDGE + x, y),
                    portrait.getRGB(UiPanelGeometry.NPC_PORTRAIT_FRAME - VanillaWindow.EDGE + x, y),
                    "portrait TR ($x,$y)",
                )
                assertEquals(
                    vanilla.getRGB(x, VanillaWindow.HEIGHT - VanillaWindow.EDGE + y),
                    portrait.getRGB(x, UiPanelGeometry.NPC_PORTRAIT_FRAME - VanillaWindow.EDGE + y),
                    "portrait BL ($x,$y)",
                )
                assertEquals(
                    vanilla.getRGB(VanillaWindow.WIDTH - VanillaWindow.EDGE + x, VanillaWindow.HEIGHT - VanillaWindow.EDGE + y),
                    portrait.getRGB(
                        UiPanelGeometry.NPC_PORTRAIT_FRAME - VanillaWindow.EDGE + x,
                        UiPanelGeometry.NPC_PORTRAIT_FRAME - VanillaWindow.EDGE + y,
                    ),
                    "portrait BR ($x,$y)",
                )
            }
        }
        val rim = UiPanelGeometry.NPC_PORTRAIT_RIM
        val wellEnd = rim + UiPanelGeometry.NPC_PORTRAIT_WELL - 1
        assertEquals(vanilla.getRGB(VanillaWindow.SLOT_X, VanillaWindow.SLOT_Y), portrait.getRGB(rim, rim))
        assertEquals(vanilla.getRGB(VanillaWindow.SLOT_X + 1, VanillaWindow.SLOT_Y + 1), portrait.getRGB(24, 24))
        assertEquals(
            vanilla.getRGB(VanillaWindow.SLOT_X + VanillaWindow.SLOT_SIZE - 1, VanillaWindow.SLOT_Y + VanillaWindow.SLOT_SIZE - 1),
            portrait.getRGB(wellEnd, wellEnd),
        )
        val solid = BufferedImage(UiPanelGeometry.NPC_PORTRAIT_PICTURE, UiPanelGeometry.NPC_PORTRAIT_PICTURE, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until solid.height) for (x in 0 until solid.width) solid.setRGB(x, y, 0xFF112233.toInt())
        val filled = DrawUiPanels.npcPortrait(vanilla, solid)
        assertEquals(vanilla.getRGB(VanillaWindow.SLOT_X, VanillaWindow.SLOT_Y), filled.getRGB(rim, rim), "slot top-left bevel stays clear")
        assertEquals(
            vanilla.getRGB(VanillaWindow.SLOT_X + VanillaWindow.SLOT_SIZE - 1, VanillaWindow.SLOT_Y + VanillaWindow.SLOT_SIZE - 1),
            filled.getRGB(wellEnd, wellEnd),
            "slot bottom-right bevel stays clear",
        )
        assertEquals(0xFF112233.toInt(), filled.getRGB(rim + 1, rim + 1))
        assertEquals(0, portrait.getRGB(24, 52), "padding below portrait frame stays clear")
    }

    @Test
    fun slotSpriteMatchesGeneric54SlotPixelForPixel() {
        val vanilla = syntheticGeneric54()
        val slot = DrawUiPanels.slotSprite(vanilla)
        assertEquals(18, slot.width)
        assertEquals(18, slot.height)
        for (y in 0 until 18) {
            for (x in 0 until 18) {
                assertEquals(vanilla.getRGB(VanillaWindow.SLOT_X + x, VanillaWindow.SLOT_Y + y), slot.getRGB(x, y))
            }
        }
    }

    @Test
    fun boardCornersAndEdgesPreserveTrimmedWidthsAndConnectSeamlessly() {
        val vanilla = syntheticGeneric54()
        val tl = DrawUiPanels.boardCorner(vanilla, DrawUiPanels.Corner.TOP_LEFT)
        val tr = DrawUiPanels.boardCorner(vanilla, DrawUiPanels.Corner.TOP_RIGHT)
        val bl = DrawUiPanels.boardCorner(vanilla, DrawUiPanels.Corner.BOTTOM_LEFT)
        val br = DrawUiPanels.boardCorner(vanilla, DrawUiPanels.Corner.BOTTOM_RIGHT)
        val vl = DrawUiPanels.boardVerticalEdge(vanilla, left = true)
        val vr = DrawUiPanels.boardVerticalEdge(vanilla, left = false)
        val ht = DrawUiPanels.boardHorizontalEdge(vanilla, 4, top = true)
        val hb = DrawUiPanels.boardHorizontalEdge(vanilla, 4, top = false)

        assertEquals(9, trimmedWidth(tl))
        assertEquals(9, trimmedWidth(bl))
        assertEquals(6, trimmedWidth(tr))
        assertEquals(6, trimmedWidth(br))
        assertEquals(6, trimmedWidth(vl))
        assertEquals(6, trimmedWidth(vr))
        assertEquals(4, trimmedWidth(ht))
        assertEquals(4, trimmedWidth(hb))

        for (i in 0 until 3) {
            assertEquals(ht.getRGB(0, 3 + i), tl.getRGB(8, 3 + i), "TL meets top edge row $i")
            assertEquals(ht.getRGB(0, 3 + i), tr.getRGB(0, 3 + i), "TR meets top edge row $i")
            assertEquals(hb.getRGB(0, 3 + i), bl.getRGB(8, 3 + i), "BL meets bottom edge row $i")
            assertEquals(hb.getRGB(0, 3 + i), br.getRGB(0, 3 + i), "BR meets bottom edge row $i")
            assertEquals(vl.getRGB(3 + i, 0), tl.getRGB(3 + i, 8), "TL meets left edge col $i")
            assertEquals(vl.getRGB(3 + i, 0), bl.getRGB(3 + i, 0), "BL meets left edge col $i")
            assertEquals(vr.getRGB(3 + i, 0), tr.getRGB(3 + i, 8), "TR meets right edge col $i")
            assertEquals(vr.getRGB(3 + i, 0), br.getRGB(3 + i, 0), "BR meets right edge col $i")
        }
    }

    private fun trimmedWidth(image: BufferedImage): Int {
        for (x in image.width - 1 downTo 0) {
            for (y in 0 until image.height) {
                if (image.getRGB(x, y) ushr 24 != 0) return x + 1
            }
        }
        return 0
    }

    private fun syntheticGeneric54(): BufferedImage {
        val image = BufferedImage(VanillaWindow.WIDTH, VanillaWindow.HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val fill = 0xFFC6C6C6.toInt()
        val shade = 0xFF555555.toInt()
        val slotDark = 0xFF373737.toInt()
        val slotMid = 0xFF8B8B8B.toInt()
        for (y in 0 until VanillaWindow.HEIGHT) {
            for (x in 0 until VanillaWindow.WIDTH) {
                val color =
                    when {
                        x == 0 || y == 0 || x == VanillaWindow.WIDTH - 1 || y == VanillaWindow.HEIGHT - 1 -> black
                        x <= 2 || y <= 2 -> white
                        x >= VanillaWindow.WIDTH - 3 || y >= VanillaWindow.HEIGHT - 3 -> shade
                        else -> fill
                    }
                image.setRGB(x, y, color)
            }
        }
        for (dy in 0 until 18) {
            for (dx in 0 until 18) {
                val color =
                    when {
                        dx == 0 || dy == 0 -> slotDark
                        dx == 17 || dy == 17 -> white
                        else -> slotMid
                    }
                image.setRGB(VanillaWindow.SLOT_X + dx, VanillaWindow.SLOT_Y + dy, color)
            }
        }
        return image
    }
}
