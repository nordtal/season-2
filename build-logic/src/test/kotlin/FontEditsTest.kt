package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class FontEditsTest {
    @TempDir
    lateinit var repository: File

    private val pack get() = repository.resolve("resource-pack")
    private val allocation get() = pack.resolve("glyphs.json")
    private val star get() = pack.resolve("src/assets/nordtal/textures/badges/star.png")
    private val starArt get() = pack.resolve("art/assets/nordtal/textures/badges/star.aseprite")
    private val arrows get() = pack.resolve("src/assets/nordtal/textures/hud/arrows.png")

    private val glyphs =
        """
        {
          "fonts": [
            {
              "font": "minecraft:default",
              "blocks": [
                {
                  "range": "FE000-FE00F",
                  "title": "Badges",
                  "height": 8,
                  "ascent": 6,
                  "glyphs": [
                    { "code": "FE000", "title": "Glyph", "constant": "BADGE_STAR", "name": "star", "texture": "nordtal:badges/star.png", "list": "BADGES" }
                  ]
                }
              ],
              "lists": [{ "constant": "BADGES" }]
            },
            {
              "font": "nordtal:hud",
              "constant": "FONT_HUD",
              "about": "The HUD.",
              "blocks": [
                {
                  "range": "FE040-FE04F",
                  "title": "Arrows",
                  "texture": "nordtal:hud/arrows.png",
                  "height": 5,
                  "ascent": 4,
                  "glyphs": [{ "code": "FE040", "title": "Glyph", "constant": "ARROW_LEFT" }, { "code": "FE041", "title": "Glyph", "constant": "ARROW_RIGHT" }]
                },
                {
                  "title": "Letters",
                  "texture": "nordtal:hud/letters.png",
                  "height": 5,
                  "ascent": 4,
                  "chars": ["AB"]
                }
              ]
            }
          ]
        }

        """.trimIndent()

    @BeforeEach
    fun fixture() {
        pack.mkdirs()
        allocation.writeText(glyphs)
        png(star, 8, 8)
        starArt.parentFile.mkdirs()
        starArt.writeText("art")
        png(arrows, 12, 5).also {
            val image = ImageIO.read(arrows)
            image.setRGB(11, 4, 0xFF00FF00.toInt())
            ImageIO.write(image, "png", arrows)
        }
        png(pack.resolve("src/assets/nordtal/textures/hud/letters.png"), 10, 5)
        git("init", "-q")
        track()
    }

    private fun png(
        file: File,
        width: Int,
        height: Int,
    ) {
        file.parentFile.mkdirs()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).inheritIO().start()
        assertEquals(0, process.waitFor())
    }

    private fun track() = git("add", "-A")

    private fun use(
        path: String,
        text: String,
    ) {
        repository.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
        track()
    }

    private fun read() = GlyphAllocation.read(allocation)

    private fun font(id: String) = read().fonts.single { it.id == id }

    @Test
    fun aNewFontComesLastWithItsConstantAndAboutAndNoBlocks() {
        val written = FontEdits.createFont(pack, "nordtal:signs", "FONT_SIGNS", "Shop signs.")

        assertEquals(listOf(allocation), written)
        val font = read().fonts.last()
        assertEquals(listOf("nordtal:signs", "FONT_SIGNS", "Shop signs.", 0), listOf(font.id, font.constant, font.about, font.blocks.size))
    }

    @Test
    fun aNewFontIsRefusedAtTheFieldItBreaks() {
        assertEquals("path", assertThrows<FieldRefusal> { FontEdits.createFont(pack, "nordtal:hud", "FONT_HUD_TWO") }.field)
        assertEquals("path", assertThrows<FieldRefusal> { FontEdits.createFont(pack, "nordtal:Signs", "FONT_SIGNS") }.field)
        assertEquals("namespace", assertThrows<FieldRefusal> { FontEdits.createFont(pack, "minecraft:signs", "FONT_SIGNS") }.field)
        assertEquals("constant", assertThrows<FieldRefusal> { FontEdits.createFont(pack, "nordtal:signs", "FONT_HUD") }.field)
        assertEquals("constant", assertThrows<FieldRefusal> { FontEdits.createFont(pack, "nordtal:signs", "") }.field)
        assertEquals(glyphs, allocation.readText())
    }

    @Test
    fun aFontEditSetsItsConstantAndRemovesAnEmptyAbout() {
        FontEdits.editFont(pack, repository, "nordtal:hud", constant = "FONT_BAR", about = "")

        val font = font("nordtal:hud")
        assertEquals("FONT_BAR", font.constant)
        assertNull(font.about)
    }

    @Test
    fun aFontEditAddsAnAboutInTheOrderOfTheFile() {
        FontEdits.editFont(pack, repository, "minecraft:default", about = "Text.")

        val keys = (OxfmtJson.read(allocation) as Map<*, *>).let { NewTextures.fonts(it)[0].keys.toList() }
        assertEquals(listOf("font", "about", "blocks", "lists"), keys)
    }

    @Test
    fun aNewFontConstantIsRefusedWhileCodeUsesTheOldOne() {
        use("smp/src/main/java/Hud.java", "class Hud {\n    String font = Glyphs.FONT_HUD;\n}\n")

        val error = assertThrows<IllegalStateException> { FontEdits.editFont(pack, repository, "nordtal:hud", constant = "FONT_BAR") }

        assertEquals("The constant FONT_HUD of nordtal:hud is still used in 1 place:\n    smp/src/main/java/Hud.java:2", error.message)
        assertEquals(glyphs, allocation.readText())
    }

    @Test
    fun theFontOfOrdinaryTextTakesNoConstant() {
        assertEquals(
            "constant",
            assertThrows<FieldRefusal> { FontEdits.editFont(pack, repository, "minecraft:default", constant = "FONT_TEXT") }.field,
        )
    }

    @Test
    fun aBlockEditSetsTitleStatusSizeAndAbout() {
        FontEdits.editBlock(pack, "minecraft:default", "Badges", "Player badges", "keep", 9, 7, "Badges of a player.")

        val block = font("minecraft:default").blocks[0]
        assertEquals(
            listOf("Player badges", "keep", 9, 7, "Badges of a player."),
            listOf(block.title, block.status, block.height, block.ascent, block.about),
        )
        assertEquals(9, block.glyphs[0].height)
        val keys = NewTextures.fonts(OxfmtJson.read(allocation) as Map<*, *>)[0].let { ((it["blocks"] as List<*>)[0] as Map<*, *>).keys }
        assertEquals(listOf("range", "title", "about", "height", "ascent", "status", "glyphs"), keys.toList())
    }

    @Test
    fun aBlockEditRemovesAnEmptyStatusAndRefusesATakenTitle() {
        FontEdits.editBlock(pack, "nordtal:hud", "Arrows", status = "keep")
        FontEdits.editBlock(pack, "nordtal:hud", "Arrows", status = "")

        assertNull(font("nordtal:hud").blocks[0].status)
        val error = assertThrows<FieldRefusal> { FontEdits.editBlock(pack, "nordtal:hud", "Arrows", title = "Letters") }
        assertEquals("title", error.field)
        assertEquals("block", assertThrows<FieldRefusal> { FontEdits.editBlock(pack, "nordtal:hud", "Comets", status = "keep") }.field)
    }

    @Test
    fun newCellsFollowTheLastCellAndWidenTheSheetByOneCellEach() {
        val written = FontEdits.addCells(pack, "nordtal:hud", "Arrows", listOf("ARROW_UP", "ARROW_DOWN"))

        assertEquals(listOf(allocation, arrows), written)
        val sheet = font("nordtal:hud").blocks[0]
        assertEquals(listOf(0xFE042, 0xFE043), sheet.glyphs.drop(2).map { it.codePoint })
        assertEquals(listOf("ARROW_UP", "ARROW_DOWN"), sheet.glyphs.drop(2).map { it.constant })
        assertEquals(listOf("Arrow up", "Arrow down"), sheet.glyphs.drop(2).map { it.title })
        val image = ImageIO.read(arrows)
        assertEquals(listOf(24, 5), listOf(image.width, image.height))
        assertEquals(0xFF00FF00.toInt(), image.getRGB(11, 4))
        assertEquals(0, image.getRGB(23, 4))
    }

    @Test
    fun cellsAreRefusedForASheetOfCharactersAndPastItsRange() {
        val letters = assertThrows<FieldRefusal> { FontEdits.addCells(pack, "nordtal:hud", "Letters", listOf("C")) }
        val full = assertThrows<FieldRefusal> { FontEdits.addCells(pack, "nordtal:hud", "Arrows", (1..15).map { "ARROW_$it" }) }

        assertEquals("block", letters.field)
        assertEquals("constants", full.field)
        assertEquals(glyphs, allocation.readText())
        assertEquals(12, ImageIO.read(arrows).width)
    }

    @Test
    fun aBlockDeleteRemovesItsGlyphsTheirSourcesAndAListLeftEmpty() {
        val written = FontEdits.deleteBlock(pack, repository, "minecraft:default", "Badges")

        assertEquals(listOf(allocation, star, starArt), written)
        assertFalse(star.exists())
        assertFalse(starArt.exists())
        val font = font("minecraft:default")
        assertEquals(listOf(emptyList<Any>(), emptyList()), listOf(font.blocks, font.lists))
    }

    @Test
    fun aBlockDeleteKeepsATextureThatAnotherGlyphDraws() {
        FontEdits.createFont(pack, "nordtal:signs", "FONT_SIGNS")
        val root = OxfmtJson.read(allocation) as OxfmtJson.Object
        @Suppress("UNCHECKED_CAST")
        (NewTextures.font(root, "nordtal:signs")["blocks"] as MutableList<Any?>) +=
            OxfmtJson.read(
                """{"range": "FE080-FE08F", "title": "Stars", "height": 8, "ascent": 6,
                   "glyphs": [{"code": "FE080", "title": "Glyph", "constant": "SIGN_STAR", "texture": "nordtal:badges/star.png"}]}""",
            )
        allocation.writeText(OxfmtJson.format(root))

        assertEquals(listOf(allocation), FontEdits.deleteBlock(pack, repository, "nordtal:signs", "Stars"))
        assertTrue(star.exists())
    }

    @Test
    fun aBlockDeleteIsRefusedWhileCodeUsesAGlyphNameOrList() {
        use("smp/src/main/java/Badge.java", "import static eu.nordtal.Glyphs.*;\nclass Badge {\n    Object all = BADGES;\n}\n")
        use("smp/src/main/resources/messages/en.properties", "a=<glyph:star>\n")

        val error = assertThrows<IllegalStateException> { FontEdits.deleteBlock(pack, repository, "minecraft:default", "Badges") }

        assertEquals(
            "Block Badges of minecraft:default is still used in 2 places:\n" +
                "    smp/src/main/java/Badge.java:3\n" +
                "    smp/src/main/resources/messages/en.properties:1",
            error.message,
        )
        assertEquals(glyphs, allocation.readText())
        assertTrue(star.exists())
    }

    @Test
    fun aSheetOfCharactersStays() {
        assertThrows<IllegalArgumentException> { FontEdits.deleteBlock(pack, repository, "nordtal:hud", "Letters") }
    }

    @Test
    fun aFontDeleteRemovesTheFontAndEveryTextureThatOnlyItDraws() {
        FontEdits.createFont(pack, "nordtal:signs", "FONT_SIGNS")
        FontEdits.deleteFont(pack, repository, "nordtal:signs")
        val root = OxfmtJson.read(allocation) as OxfmtJson.Object

        @Suppress("UNCHECKED_CAST")
        val fonts = root["fonts"] as MutableList<Any?>
        // A font without characters can go too
        fonts.removeAt(1)
        fonts +=
            OxfmtJson.read(
                """{"font": "nordtal:hud", "constant": "FONT_HUD", "blocks": [{"range": "FE040-FE04F", "title": "Arrows",
                   "texture": "nordtal:hud/arrows.png", "height": 5, "ascent": 4, "glyphs": [{"code": "FE040", "title": "Glyph", "constant": "ARROW_LEFT"}]}]}""",
            )
        allocation.writeText(OxfmtJson.format(root))

        val written = FontEdits.deleteFont(pack, repository, "nordtal:hud")

        assertEquals(listOf(allocation, arrows), written)
        assertFalse(arrows.exists())
        assertEquals(listOf("minecraft:default"), read().fonts.map { it.id })
    }

    @Test
    fun aFontDeleteIsRefusedForOrdinaryTextAndWhileCodeUsesItsIdOrConstant() {
        use("build.gradle.kts", "advances.put(\"nordtal:hud\", \"hud.properties\")\n")
        use("smp/src/main/kotlin/Hud.kt", "val left = Glyphs.ARROW_LEFT\n")
        use("README.md", "The font \"nordtal:hud\" draws the HUD.\n")
        use("smp/src/test/kotlin/HudTest.kt", "val font = \"nordtal:hud\"\n")
        use("designer/spec/pack_spec.lua", "local font = \"nordtal:hud\"\n")

        val error = assertThrows<IllegalStateException> { FontEdits.deleteFont(pack, repository, "nordtal:hud") }

        assertEquals(
            "The font nordtal:hud is still used in 2 places:\n    build.gradle.kts:1\n    smp/src/main/kotlin/Hud.kt:1",
            error.message,
        )
        assertThrows<IllegalArgumentException> { FontEdits.deleteFont(pack, repository, "minecraft:default") }
        assertEquals(glyphs, allocation.readText())
    }
}
