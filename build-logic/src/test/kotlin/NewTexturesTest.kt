package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import javax.imageio.ImageIO

class NewTexturesTest {
    @TempDir
    lateinit var pack: File

    private val allocation get() = pack.resolve("glyphs.json")

    private fun fixture() {
        allocation.writeText(
            """
            {
              "fonts": [
                {
                  "font": "minecraft:default",
                  "blocks": [
                    {
                      "range": "FE000-FE00F",
                      "title": "Badges",
                      "height": 7,
                      "ascent": 7,
                      "glyphs": [
                        {
                          "code": "FE000",
                          "constant": "STAR",
                          "name": "star",
                          "texture": "nordtal:badges/star.png"
                        }
                      ]
                    },
                    {
                      "range": "FE020-FE02F",
                      "title": "Icons",
                      "texture": "nordtal:icons.png",
                      "height": 8,
                      "ascent": 7,
                      "glyphs": [{ "code": "FE020", "constant": "SPAWN", "name": "spawn" }]
                    }
                  ]
                },
                {
                  "font": "nordtal:hud",
                  "blocks": [{ "range": "FE040-FE041", "title": "Bars", "glyphs": [] }]
                }
              ]
            }

            """.trimIndent(),
        )
    }

    @Test
    fun aGlyphTakesTheNextCodePointOfItsBlockAndATransparentPng() {
        fixture()

        val written = NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/moon.png", null, "half-moon", 9, 7)

        val png = pack.resolve("src/assets/nordtal/textures/badges/moon.png")
        assertEquals(listOf(allocation, png), written)
        val image = ImageIO.read(png)
        assertEquals(listOf(9, 7), listOf(image.width, image.height))
        assertEquals(0, image.getRGB(4, 3) ushr 24)
        val glyph = GlyphAllocation.read(allocation).fonts[0].glyphs[1]
        assertEquals(
            listOf(0xFE001, "HALF_MOON", "half-moon", 7, 7),
            listOf(glyph.codePoint, glyph.constant, glyph.name, glyph.height, glyph.ascent),
        )
        assertTrue(
            allocation.readText().contains(
                """
                  {
                    "code": "FE001",
                    "constant": "HALF_MOON",
                    "name": "half-moon",
                    "texture": "nordtal:badges/moon.png"
                  }
                ]
                """.trimIndent().prependIndent("          "),
            ),
            allocation.readText(),
        )
    }

    @Test
    fun aGlyphWithAnotherPixelHeightKeepsItAsItsDisplayHeight() {
        fixture()

        NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/tall.png", "TALL", "tall", 5, 14, ascent = 10)

        val glyph = GlyphAllocation.read(allocation).fonts[0].glyphs[1]
        assertEquals(listOf(14, 10), listOf(glyph.height, glyph.ascent))
    }

    @Test
    fun theFileKeepsItsLayoutAroundTheNewEntry() {
        fixture()
        val before = allocation.readText()

        NewTextures.glyph(pack, "nordtal:hud", "Bars", "nordtal:hud/bar.png", "BAR", null, 4, 4, ascent = 0)

        val after = allocation.readText()
        assertEquals(before.substringBefore("\"font\": \"nordtal:hud\""), after.substringBefore("\"font\": \"nordtal:hud\""))
        assertEquals(OxfmtJson.format(OxfmtJson.read(after)), after)
    }

    @Test
    fun aGlyphRefusesAPathThatExists() {
        fixture()
        pack.resolve("src/assets/nordtal/textures/badges").mkdirs()
        pack.resolve("src/assets/nordtal/textures/badges/star.png").writeText("")
        val before = allocation.readText()

        val refusal =
            assertThrows<FieldRefusal> {
                NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/star.png", null, "star-two", 7, 7)
            }

        assertTrue("src/assets/nordtal/textures/badges/star.png exists already" in refusal.message!!, refusal.message)
        assertEquals(before, allocation.readText())
    }

    @Test
    fun aGlyphRefusesWhatGlyphsJsonForbidsAndWritesNothing() {
        fixture()
        val before = allocation.readText()

        val refusal =
            assertThrows<FieldRefusal> {
                NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/other.png", null, "star", 7, 7)
            }

        assertTrue("name star is declared twice" in refusal.message!!, refusal.message)
        assertEquals(before, allocation.readText())
        assertFalse(pack.resolve("src/assets/nordtal/textures/badges/other.png").exists())
    }

    @Test
    fun aGlyphRefusesASheetAFullBlockAndAnUnknownPlace() {
        fixture()
        val refusals =
            mutableListOf<() -> Unit>(
                { NewTextures.glyph(pack, "minecraft:default", "Icons", "nordtal:x.png", null, "x", 8, 8) },
                { NewTextures.glyph(pack, "nordtal:none", "Bars", "nordtal:hud/c.png", "BAR_C", null, 4, 4) },
                { NewTextures.glyph(pack, "nordtal:hud", "None", "nordtal:hud/c.png", "BAR_C", null, 4, 4) },
                { NewTextures.glyph(pack, "nordtal:hud", "Bars", "nordtal:hud/c.png", null, null, 4, 4, ascent = 0) },
                { NewTextures.glyph(pack, "nordtal:hud", "Bars", "nordtal:hud/c.png", "BAR_C", null, 4, 4) },
            )
        val messages = refusals.map { assertThrows<IllegalArgumentException> { it() }.message }
        NewTextures.glyph(pack, "nordtal:hud", "Bars", "nordtal:hud/a.png", "BAR_A", null, 4, 4, ascent = 0)
        NewTextures.glyph(pack, "nordtal:hud", "Bars", "nordtal:hud/b.png", "BAR_B", null, 4, 4, ascent = 0)
        val full =
            assertThrows<IllegalArgumentException> {
                NewTextures.glyph(pack, "nordtal:hud", "Bars", "nordtal:hud/c.png", "BAR_C", null, 4, 4, ascent = 0)
            }

        assertEquals(
            listOf(
                "Block Icons of minecraft:default is a sheet, whose cells are drawn in its one texture.",
                "glyphs.json has no font nordtal:none.",
                "The font nordtal:hud has no block None.",
                "A glyph needs a constant or a name.",
                "Block Bars of nordtal:hud sets no ascent, so the glyph needs one.",
                "Block Bars of nordtal:hud has no free code point left in FE040-FE041.",
            ),
            messages + full.message,
        )
    }

    @Test
    fun eachRefusalNamesTheFieldItConcerns() {
        fixture()
        pack.resolve("src/assets/nordtal/textures/badges").mkdirs()
        pack.resolve("src/assets/nordtal/textures/badges/star.png").writeText("")
        val refusals =
            listOf<Pair<String, () -> Unit>>(
                "path" to { NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/star.png", null, "two", 7, 7) },
                "path" to { NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:X.png", null, "two", 7, 7) },
                "path" to { NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:x", null, "two", 7, 7) },
                "namespace" to { NewTextures.glyph(pack, "minecraft:default", "Badges", "Nordtal:x.png", null, "two", 7, 7) },
                "font" to { NewTextures.glyph(pack, "nordtal:none", "Bars", "nordtal:hud/c.png", "BAR_C", null, 4, 4) },
                "block" to { NewTextures.glyph(pack, "nordtal:hud", "None", "nordtal:hud/c.png", "BAR_C", null, 4, 4) },
                "block" to { NewTextures.glyph(pack, "minecraft:default", "Icons", "nordtal:x.png", null, "x", 8, 8) },
                "name" to { NewTextures.glyph(pack, "nordtal:hud", "Bars", "nordtal:hud/c.png", null, null, 4, 4, ascent = 0) },
                "name" to { NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/two.png", null, "star", 7, 7) },
                "name" to { NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/two.png", null, "Two", 7, 7) },
                "constant" to { NewTextures.glyph(pack, "nordtal:hud", "Bars", "nordtal:hud/c.png", "bar", null, 4, 4, ascent = 0) },
                "ascent" to { NewTextures.glyph(pack, "nordtal:hud", "Bars", "nordtal:hud/c.png", "BAR_C", null, 4, 4) },
                "width" to { NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/two.png", null, "two", 5000, 7) },
                "height" to { NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/two.png", null, "two", 7, 0) },
                "title" to { NewTextures.block(pack, "minecraft:default", "Badges") },
                "title" to { NewTextures.block(pack, "minecraft:default", "Clash", range = "FE008-FE017") },
                "size" to { NewTextures.block(pack, "minecraft:default", "Huge", size = 0x20000) },
                "constants" to { NewTextures.sheet(pack, "nordtal:hud", "Empty", "nordtal:hud/empty.png", emptyList(), 4, 4, 3) },
                "constants" to { NewTextures.sheet(pack, "nordtal:hud", "Twice", "nordtal:hud/twice.png", listOf("A", "A"), 4, 4, 3) },
                "width" to { NewTextures.sheet(pack, "nordtal:hud", "Wide", "nordtal:hud/wide.png", (1..300).map { "W$it" }, 16, 8, 7) },
                "path" to { NewTextures.item(pack, "nordtal:Wand", 16, 16) },
                "namespace" to { NewTextures.item(pack, "Nordtal:wand", 16, 16) },
            )
        val before = allocation.readText()

        val fields = refusals.map { (_, refused) -> assertThrows<FieldRefusal> { refused() }.field }

        assertEquals(refusals.map { it.first }, fields)
        assertEquals(before, allocation.readText())
        NewTextures.item(pack, "nordtal:wand", 16, 16)
        assertEquals("path", assertThrows<FieldRefusal> { NewTextures.item(pack, "nordtal:wand", 16, 16) }.field)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "badges/x.png | is not namespace:path, with exactly one colon",
            "nord:tal:x.png | is not namespace:path, with exactly one colon",
            "Nordtal:x.png | The namespace of Nordtal:x.png may hold only",
            "..:x.png | The namespace of ..:x.png ends in .",
            "nordtal.:x.png | The namespace of nordtal.:x.png ends in .",
            "nordtal:X.png | The path of nordtal:X.png may hold only",
            "nordtal:a b.png | The path of nordtal:a b.png may hold only",
            "nordtal:a//b.png | The path of nordtal:a//b.png may hold only",
            "nordtal:/x.png | The path of nordtal:/x.png may hold only",
            "nordtal:x/ | The path of nordtal:x/ may hold only",
            "nordtal:ä.png | may hold only",
            "nordtal:../x.png | A name in the path of nordtal:../x.png ends in .",
            "nordtal:./x.png | A name in the path of nordtal:./x.png ends in .",
            "nordtal:a./x.png | A name in the path of nordtal:a./x.png ends in .",
            "nordtal:x | nordtal:x does not end in a name and .png.",
            "nordtal:.png | nordtal:.png does not end in a name and .png.",
        ],
    )
    fun anInvalidTextureIdIsRefused(
        id: String,
        rule: String,
    ) {
        fixture()
        val before = allocation.readText()

        val refusal = assertThrows<IllegalArgumentException> { NewTextures.glyph(pack, "minecraft:default", "Badges", id, null, "x", 7, 7) }

        assertTrue(rule in refusal.message!!, refusal.message)
        assertEquals(before, allocation.readText())
        assertFalse(pack.resolve("src").exists())
    }

    @Test
    fun glyphsJsonRefusesATextureThatIsNoIdentifier() {
        fixture()
        allocation.writeText(allocation.readText().replace("nordtal:badges/star.png", "nordtal:badges/../star.png"))

        val refusal = assertThrows<IllegalStateException> { GlyphAllocation.read(allocation) }

        assertTrue(
            "'texture' is a PNG such as nordtal:badges/star.png, but a name in the path of nordtal:badges/../star.png ends in ." in
                refusal.message!!,
            refusal.message,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["nordtal:a/b_c-d.e.png", "my.pack-1_2:x.png", "minecraft:0.png"])
    fun aValidIdentifierIsAccepted(id: String) = assertNull(Identifier.problem(id))

    @Test
    fun aBlockTakesTheFirstFreeRangeAndKeepsTheBlocksInOrder() {
        fixture()

        NewTextures.block(pack, "minecraft:default", "Flags", height = 7, ascent = 7)
        NewTextures.block(pack, "minecraft:default", "Hearts", size = 32)

        val blocks = GlyphAllocation.read(allocation).fonts[0].blocks
        assertEquals(listOf("Badges", "Flags", "Icons", "Hearts"), blocks.map { it.title })
        assertEquals(0xFE010..0xFE01F, blocks[1].range)
        // FE020-FE02F and FE040-FE041 of the other font are taken, so 32 code points start at FE060.
        assertEquals(0xFE060..0xFE07F, blocks[3].range)
        assertEquals(listOf(7, 7), listOf(blocks[1].height, blocks[1].ascent))
    }

    @Test
    fun aBlockRefusesATitleThatExists() {
        fixture()

        val refusal = assertThrows<IllegalArgumentException> { NewTextures.block(pack, "minecraft:default", "Badges") }

        assertEquals("The font minecraft:default has a block Badges already.", refusal.message)
    }

    @Test
    fun aSheetDrawsItsCellsInOneRowOfItsTexture() {
        fixture()

        val written =
            NewTextures.sheet(pack, "nordtal:hud", "Arrows", "nordtal:hud/arrows.png", listOf("LEFT", "RIGHT", "UP"), 6, 5, ascent = -4)

        val png = pack.resolve("src/assets/nordtal/textures/hud/arrows.png")
        assertEquals(listOf(allocation, png), written)
        assertEquals(listOf(18, 5), ImageIO.read(png).let { listOf(it.width, it.height) })
        val sheet = GlyphAllocation.read(allocation).fonts[1].blocks[0]
        assertEquals(listOf("nordtal:hud/arrows.png", 5, -4), listOf(sheet.texture, sheet.height, sheet.ascent))
        assertEquals(listOf(0xFE010, 0xFE011, 0xFE012), sheet.glyphs.map { it.codePoint })
        assertTrue("""{ "code": "FE010", "constant": "LEFT" },""" in allocation.readText(), allocation.readText())
    }

    @Test
    fun anItemIsADefinitionAModelAndAPng() {
        val written = NewTextures.item(pack, "nordtal:tools/wand", 16, 32)

        val assets = pack.resolve("src/assets/nordtal")
        assertEquals(
            listOf(
                assets.resolve("items/tools/wand.json"),
                assets.resolve("models/item/tools/wand.json"),
                assets.resolve("textures/item/tools/wand.png"),
            ),
            written,
        )
        assertEquals(
            mapOf("model" to mapOf("type" to "minecraft:model", "model" to "nordtal:item/tools/wand")),
            OxfmtJson.read(written[0]),
        )
        assertEquals(
            mapOf("parent" to "minecraft:item/generated", "textures" to mapOf("layer0" to "nordtal:item/tools/wand")),
            OxfmtJson.read(written[1]),
        )
        assertEquals(listOf(16, 32), ImageIO.read(written[2]).let { listOf(it.width, it.height) })
        assertEquals(
            """
            {
                "parent": "minecraft:item/generated",
                "textures": {
                    "layer0": "nordtal:item/tools/wand"
                }
            }

            """.trimIndent(),
            written[1].readText(),
        )
    }

    @Test
    fun anItemRefusesAnInvalidIdAndAFileThatExists() {
        NewTextures.item(pack, "nordtal:wand", 16, 16)

        val invalid = assertThrows<IllegalArgumentException> { NewTextures.item(pack, "nordtal:Wand", 16, 16) }
        assertEquals("The path of nordtal:Wand may hold only a-z, 0-9, _, -, . and /.", invalid.message)
        val refusal = assertThrows<FieldRefusal> { NewTextures.item(pack, "nordtal:wand", 16, 16) }
        assertEquals("src/assets/nordtal/items/wand.json exists already.", refusal.message)
    }

    @Test
    fun aTextureOverTheSizeLimitWritesNothing() {
        fixture()
        val before = allocation.readText()

        assertThrows<IllegalArgumentException> { NewTextures.item(pack, "nordtal:wand", 5000, 16) }
        assertThrows<IllegalArgumentException> {
            NewTextures.sheet(pack, "nordtal:hud", "Wide", "nordtal:hud/wide.png", (1..300).map { "W$it" }, 16, 8, 7)
        }

        assertFalse(pack.resolve("src").exists())
        assertEquals(before, allocation.readText())
    }

    @Test
    fun aGlyphWhosePngCannotBeWrittenLeavesGlyphsJsonAsItWas() {
        fixture()
        pack.resolve("src/assets/nordtal/textures").mkdirs()
        pack.resolve("src/assets/nordtal/textures/badges").writeText("a file where the folder belongs")
        val before = allocation.readText()

        val refusal =
            assertThrows<IllegalStateException> {
                NewTextures.glyph(pack, "minecraft:default", "Badges", "nordtal:badges/moon.png", null, "moon", 7, 7)
            }

        assertTrue("so nothing was written" in refusal.message!!, refusal.message)
        assertEquals(before, allocation.readText())
        assertEquals(
            listOf("badges", "glyphs.json"),
            pack
                .walk()
                .filter { it.isFile }
                .map { it.name }
                .sorted()
                .toList(),
        )
    }

    @Test
    fun anItemWhosePngCannotBeWrittenWritesNoFile() {
        pack.resolve("src/assets/nordtal").mkdirs()
        pack.resolve("src/assets/nordtal/textures").writeText("a file where the folder belongs")

        val refusal = assertThrows<IllegalStateException> { NewTextures.item(pack, "nordtal:wand", 16, 16) }

        assertTrue("so nothing was written" in refusal.message!!, refusal.message)
        assertEquals(
            listOf("textures"),
            pack
                .walk()
                .filter { it.isFile }
                .map { it.name }
                .toList(),
        )
        assertFalse(pack.resolve("src/assets/nordtal/items").exists())
    }

    @Test
    fun aSheetRefusesAPngThatExistsNoCellsAndARangeThatOverlapsABlock() {
        fixture()
        pack.resolve("src/assets/nordtal/textures/hud").mkdirs()
        pack.resolve("src/assets/nordtal/textures/hud/taken.png").writeText("")
        val before = allocation.readText()

        val existing =
            assertThrows<FieldRefusal> {
                NewTextures.sheet(pack, "nordtal:hud", "Taken", "nordtal:hud/taken.png", listOf("A"), 4, 4, 3)
            }
        val empty =
            assertThrows<IllegalArgumentException> {
                NewTextures.sheet(pack, "nordtal:hud", "Empty", "nordtal:hud/empty.png", emptyList(), 4, 4, 3)
            }
        val overlap =
            assertThrows<FieldRefusal> {
                NewTextures.sheet(pack, "minecraft:default", "Clash", "nordtal:clash.png", listOf("A"), 4, 4, 3, range = "FE008-FE017")
            }

        assertEquals("src/assets/nordtal/textures/hud/taken.png exists already.", existing.message)
        assertEquals("A sheet needs at least one cell.", empty.message)
        assertTrue("block Clash overlaps" in overlap.message!!, overlap.message)
        assertEquals(before, allocation.readText())
        assertFalse(pack.resolve("src/assets/nordtal/textures/clash.png").exists())
    }
}
