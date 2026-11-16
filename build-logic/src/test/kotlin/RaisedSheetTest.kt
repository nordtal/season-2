package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage

class RaisedSheetTest {
    private val ink = 0xFF404040.toInt()

    /** Two cell rows of 2 x 3, each with one drawn pixel at its own height. */
    private fun sheet(): BufferedImage =
        BufferedImage(2, 6, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, ink)
            setRGB(1, 5, ink)
        }

    @Test
    fun eachCellRowKeepsItsPixelsOnTopOfTransparentRows() {
        val padded = RaisedSheet.pad(sheet(), 2, 3, 5)

        assertEquals(2, padded.width)
        assertEquals(10, padded.height)
        val drawn = (0 until 10).flatMap { y -> (0 until 2).filter { x -> padded.getRGB(x, y) ushr 24 != 0 }.map { x -> x to y } }
        assertEquals(listOf(0 to 0, 1 to 7), drawn)
    }

    @Test
    fun aSheetDrawnAtAnotherScaleIsRefused() {
        assertThrows<IllegalStateException> { RaisedSheet.pad(sheet(), 2, 2, 5) }
    }

    @Test
    fun theCopyIsNamedAfterItsSheetAndHeight() {
        assertEquals("nordtal:font/raised_64_ascii.png", RaisedSheet.id("minecraft:font/ascii.png", 64))
    }
}
