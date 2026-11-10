package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class ChestPanelsTest {
    @Test
    fun glyphsJsonDeclaresTheChestPanelsAtTheSizeAndAscentThatTheBuildDrawsThem() {
        val root = File(System.getProperty("nordtal.resourcePack"))
        val panel = GlyphAllocation.read(root.resolve("glyphs.json")).fonts.single { it.id == "nordtal:ui_panel" }
        val glyphs = panel.blocks.flatMap { it.glyphs }
        assertEquals(12, glyphs.size)
        for (glyph in glyphs) {
            val rows = Regex("chest_(plain_)?([1-6])\\.png").find(glyph.texture)!!.groupValues[2].toInt()
            assertEquals(ChestPanelGeometry.height(rows), glyph.height, glyph.texture)
            assertEquals(ChestPanelGeometry.ASCENT, glyph.ascent, glyph.texture)
        }
    }
}
