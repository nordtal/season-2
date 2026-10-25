package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class GlyphPreviewArgumentsTest {
    @TempDir
    lateinit var assets: File

    @Test
    fun aGlyphInASheetIsAsWideAsItsCellDrawsNotAsTheSheet() {
        // Two 16 by 16 cells: the first paints columns 0 to 9, the second columns 0 to 3.
        val sheet = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) {
            for (x in 0..9) sheet.setRGB(x, y, -1)
            for (x in 16..19) sheet.setRGB(x, y, -1)
        }
        assets.resolve("nordtal/textures/ui").mkdirs()
        ImageIO.write(sheet, "png", assets.resolve("nordtal/textures/ui/sheet.png"))
        assets.resolve("nordtal/font").mkdirs()
        assets.resolve("nordtal/font/gui_r0.json").writeText(
            """{"providers": [{"type": "bitmap", "file": "nordtal:ui/sheet.png", "height": 16, "ascent": 0, "chars": [""]}]}""",
        )

        assertEquals(10, GlyphPreviewArguments.width(assets, "nordtal:gui_r{row}", "E000"))
        assertEquals(4, GlyphPreviewArguments.width(assets, "nordtal:gui_r{row}", "E001"))
        assertThrows(IllegalArgumentException::class.java) { GlyphPreviewArguments.width(assets, "nordtal:gui_r{row}", "E002") }
    }
}
