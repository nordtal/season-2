package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class TextureMetaTest {
    @TempDir
    lateinit var pack: File

    private val lava = "minecraft:block/lava_still.png"
    private val lavaPng get() = pack.resolve("src/assets/minecraft/textures/block/lava_still.png")
    private val lavaMeta get() = File(lavaPng.path + ".mcmeta")
    private val button = "minecraft:gui/sprites/widget/button.png"
    private val buttonPng get() = pack.resolve("src/assets/minecraft/textures/gui/sprites/widget/button.png")
    private val buttonMeta get() = File(buttonPng.path + ".mcmeta")

    private fun png(
        file: File,
        width: Int,
        height: Int,
    ) {
        file.parentFile.mkdirs()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
    }

    @Test
    fun framesOfOneLengthWriteOneFrametimeInTicks() {
        png(lavaPng, 16, 48)

        val written = TextureMeta.animate(pack, lava, listOf(100, 100, 100))

        assertEquals(listOf(lavaMeta), written)
        assertEquals("{\n    \"animation\": {\n        \"frametime\": 2\n    }\n}\n", lavaMeta.readText())
    }

    @Test
    fun framesOfDifferentLengthsListEachFrameWithItsTicksAndAFrameShorterThanATickLastsOne() {
        png(lavaPng, 16, 48)

        TextureMeta.animate(pack, lava, listOf(500, 20, 175))

        assertEquals(
            mapOf("animation" to mapOf("frames" to listOf(mapOf("index" to 0, "time" to 10), mapOf("index" to 1, "time" to 1), mapOf("index" to 2, "time" to 4)))),
            OxfmtJson.read(lavaMeta),
        )
    }

    @Test
    fun aFrameThatIsNotSquareNamesItsHeight() {
        png(lavaPng, 16, 30)

        TextureMeta.animate(pack, lava, listOf(100, 100))

        assertEquals(mapOf("animation" to mapOf("frametime" to 2, "height" to 15)), OxfmtJson.read(lavaMeta))
    }

    @Test
    fun anAnimationKeepsInterpolateAndTheOtherMembersOfTheMcmeta() {
        png(lavaPng, 16, 32)
        lavaMeta.writeText("""{"texture": {"blur": true}, "animation": {"frametime": 3, "interpolate": true, "frames": [0, 1, 0]}}""")

        TextureMeta.animate(pack, lava, listOf(50, 50))

        assertEquals(
            mapOf("texture" to mapOf("blur" to true), "animation" to mapOf("frametime" to 1, "interpolate" to true)),
            OxfmtJson.read(lavaMeta),
        )
    }

    @Test
    fun oneFrameRemovesTheAnimationAndAnMcmetaLeftEmpty() {
        png(lavaPng, 16, 16)
        lavaMeta.writeText("""{"animation": {"frametime": 3}}""")

        val written = TextureMeta.animate(pack, lava, listOf(100))

        assertEquals(listOf(lavaMeta), written)
        assertFalse(lavaMeta.exists())
    }

    @Test
    fun anAnimationRefusesAPngThatIsNoColumnOfItsFrames() {
        png(lavaPng, 16, 40)

        val error = assertThrows<IllegalArgumentException> { TextureMeta.animate(pack, lava, listOf(100, 100, 100)) }

        assertEquals("src/assets/minecraft/textures/block/lava_still.png is 40 pixels high, which 3 frames do not divide.", error.message)
        assertFalse(lavaMeta.exists())
    }

    @Test
    fun aNineSliceWithOneBorderWritesItAsOneNumber() {
        png(buttonPng, 200, 20)

        val written = TextureMeta.scale(pack, button, "nine_slice", 200, 20, listOf(3, 3, 3, 3), stretchInner = false)

        assertEquals(listOf(buttonMeta), written)
        assertEquals(
            "{\n    \"gui\": {\n        \"scaling\": {\n            \"type\": \"nine_slice\",\n" +
                "            \"width\": 200,\n            \"height\": 20,\n            \"border\": 3\n        }\n    }\n}\n",
            buttonMeta.readText(),
        )
    }

    @Test
    fun aNineSliceWithDifferentBordersNamesEachSideAndStretchesItsInnerPartWhenAsked() {
        png(buttonPng, 130, 24)

        TextureMeta.scale(pack, button, "nine_slice", 130, 24, listOf(2, 2, 2, 0), stretchInner = true)

        assertEquals(
            mapOf(
                "gui" to
                    mapOf(
                        "scaling" to
                            mapOf(
                                "type" to "nine_slice",
                                "width" to 130,
                                "height" to 24,
                                "border" to mapOf("left" to 2, "top" to 2, "right" to 2, "bottom" to 0),
                                "stretch_inner" to true,
                            ),
                    ),
            ),
            OxfmtJson.read(buttonMeta),
        )
    }

    @Test
    fun aTileWritesItsSizeAndStretchRemovesTheScalingAndAnMcmetaLeftEmpty() {
        png(buttonPng, 200, 20)

        TextureMeta.scale(pack, button, "tile", 100, 10, null, null)
        val tile = OxfmtJson.read(buttonMeta)
        TextureMeta.scale(pack, button, "stretch", null, null, null, null)

        assertEquals(mapOf("gui" to mapOf("scaling" to mapOf("type" to "tile", "width" to 100, "height" to 10))), tile)
        assertFalse(buttonMeta.exists())
    }

    @Test
    fun scalingRefusesATextureOutsideTheGuiSprites() {
        png(lavaPng, 16, 16)

        val error = assertThrows<IllegalArgumentException> { TextureMeta.scale(pack, lava, "tile", 16, 16, null, null) }

        assertEquals("minecraft:block/lava_still.png is no GUI sprite, which lies under textures/gui/sprites/.", error.message)
    }

    @Test
    fun aNineSliceRefusesAMissingSizeAndABorderWiderThanTheSprite() {
        png(buttonPng, 20, 20)

        val size = assertThrows<IllegalArgumentException> { TextureMeta.scale(pack, button, "nine_slice", null, 20, listOf(3, 3, 3, 3), false) }
        val border = assertThrows<IllegalArgumentException> { TextureMeta.scale(pack, button, "nine_slice", 40, 40, listOf(12, 0, 10, 0), false) }
        val type = assertThrows<IllegalArgumentException> { TextureMeta.scale(pack, button, "slice", 40, 40, null, null) }

        assertEquals("A nine_slice scaling needs a width and a height of at least 1 pixel.", size.message)
        assertEquals("The left and right border of 12 and 10 pixels are wider than the sprite of 20 pixels.", border.message)
        assertEquals("The scaling is stretch, tile or nine_slice, not slice.", type.message)
    }

    @Test
    fun theFramesOfAColumnFollowTheFrametimeInMilliseconds() {
        assertEquals(
            listOf(frame(0, 0, 10, 28, 500), frame(0, 28, 10, 28, 500)),
            TextureMeta.frames(10, 56, mapOf("frametime" to 10, "height" to 28)),
        )
    }

    @Test
    fun theFramesOfARowAreSquaresOfTheSmallerSideAndAListPlaysEachEntryWithItsTime() {
        assertEquals(
            listOf(frame(16, 0, 16, 16, 100), frame(0, 0, 16, 16, 450), frame(16, 0, 16, 16, 100)),
            TextureMeta.frames(32, 16, mapOf("frametime" to 2, "frames" to listOf(1, mapOf("index" to 0, "time" to 9), 1))),
        )
    }

    @Test
    fun theFramesOfAGridRunRowByRowAndAFrametimeDefaultsToOneTick() {
        assertEquals(
            listOf(frame(0, 0, 8, 8, 50), frame(8, 0, 8, 8, 50), frame(0, 8, 8, 8, 50), frame(8, 8, 8, 8, 50)),
            TextureMeta.frames(16, 16, mapOf("width" to 8, "height" to 8)),
        )
    }

    @Test
    fun aPngThatItsFramesDoNotDivideHasNoFrames() {
        assertEquals(null, TextureMeta.frames(16, 40, mapOf("height" to 16)))
        assertEquals(null, TextureMeta.frames(16, 32, mapOf("frames" to listOf(0, 5))))
    }

    private fun frame(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        duration: Int,
    ) = mapOf("x" to x, "y" to y, "width" to width, "height" to height, "duration" to duration)
}
