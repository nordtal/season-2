package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class GlyphEditsTest {
    @TempDir
    lateinit var repository: File

    private val pack get() = repository.resolve("resource-pack")
    private val allocation get() = pack.resolve("glyphs.json")
    private val star get() = pack.resolve("src/assets/nordtal/textures/badges/star.png")
    private val starArt get() = pack.resolve("art/assets/nordtal/textures/badges/star.aseprite")

    private fun glyphs(vararg badges: String) =
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
                    ${badges.joinToString(",\n")}
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
              "blocks": [{ "range": "FE040-FE04F", "title": "Bars", "height": 5, "ascent": 5, "glyphs": [] }]
            }
          ]
        }

        """.trimIndent()

    private fun badge(
        code: String,
        name: String,
        constant: String = name.uppercase().replace('-', '_'),
    ) = """{ "code": "$code", "constant": "$constant", "name": "$name", "texture": "nordtal:badges/$name.png" }"""

    @BeforeEach
    fun fixture() {
        pack.mkdirs()
        allocation.writeText(glyphs(badge("FE000", "star", "BADGE_STAR"), badge("FE001", "moon")))
        png(star, 8)
        png(pack.resolve("src/assets/nordtal/textures/badges/moon.png"), 8)
        starArt.parentFile.mkdirs()
        starArt.writeText("art")
        git("init", "-q")
        track()
    }

    private fun png(
        file: File,
        height: Int,
    ) {
        file.parentFile.mkdirs()
        ImageIO.write(BufferedImage(height, height, BufferedImage.TYPE_INT_ARGB), "png", file)
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

    private fun glyph(name: String) =
        GlyphAllocation
            .read(allocation)
            .fonts
            .flatMap { it.glyphs }
            .single { it.name == name }

    @Test
    fun aRenameChangesTheNameAndTheConstantFollowsIt() {
        val written = GlyphEdits.rename(pack, repository, "star", "sun-star")

        assertEquals(listOf(allocation), written)
        val glyph = glyph("sun-star")
        assertEquals(listOf(0xFE000, "SUN_STAR", "nordtal:badges/star.png"), listOf(glyph.codePoint, glyph.constant, glyph.texture))
    }

    @Test
    fun aRenameFindsTheGlyphByItsConstantToo() {
        GlyphEdits.rename(pack, repository, "BADGE_STAR", "sun")

        assertEquals("SUN", glyph("sun").constant)
    }

    @Test
    fun aRenameAndADeleteRefuseWhileTheConstantOrATagUsesTheGlyphAndListEveryPlace() {
        use("smp/src/main/java/Badge.java", "class Badge {\n    String star = Glyphs.BADGE_STAR;\n}\n")
        use("smp/src/main/resources/messages/en.properties", "a=<glyph:moon>\nb=<glyph:star> and <glyph:star>\n")
        val before = allocation.readText()

        val rename = assertThrows<IllegalStateException> { GlyphEdits.rename(pack, repository, "star", "sun") }
        val delete = assertThrows<IllegalStateException> { GlyphEdits.delete(pack, repository, "star") }

        val places =
            "star is still used in 2 places:\n" +
                "    smp/src/main/java/Badge.java:2\n" +
                "    smp/src/main/resources/messages/en.properties:2"
        assertEquals(places, rename.message)
        assertEquals(places, delete.message)
        assertEquals(before, allocation.readText())
        assertTrue(star.exists())
    }

    @Test
    fun aConstantThatOnlyContainsTheGlyphsConstantIsNoUse() {
        use("smp/src/main/java/Badge.java", "class Badge {\n    String star = Glyphs.BADGE_STAR_GOLD + BADGE_STAR;\n}\n")
        use("docs/tags.md", "<glyph:stars> <glyph:star-gold>\n")

        GlyphEdits.rename(pack, repository, "star", "sun")
    }

    @Test
    fun aRenameRefusesANameThatGlyphsJsonForbids() {
        val error = assertThrows<IllegalStateException> { GlyphEdits.rename(pack, repository, "star", "moon") }

        assertTrue(error.message!!.contains("name moon is declared twice"), error.message)
    }

    @Test
    fun anUnknownGlyphIsRefused() {
        val error = assertThrows<IllegalArgumentException> { GlyphEdits.delete(pack, repository, "comet") }

        assertEquals("glyphs.json has no glyph named comet or with the constant comet.", error.message)
    }

    @Test
    fun aDeleteRemovesTheEntryItsPngAndItsAsepriteSource() {
        val written = GlyphEdits.delete(pack, repository, "star")

        assertEquals(listOf(allocation, star, starArt), written)
        assertFalse(star.exists())
        assertFalse(starArt.exists())
        assertEquals(
            listOf("moon"),
            GlyphAllocation
                .read(allocation)
                .fonts[0]
                .blocks[0]
                .glyphs
                .map { it.name },
        )
    }

    @Test
    fun aDeleteOfASheetCellRemovesOnlyItsEntry() {
        GlyphEdits.delete(pack, repository, "spawn")

        assertEquals(
            emptyList<Any>(),
            GlyphAllocation
                .read(allocation)
                .fonts[0]
                .blocks[1]
                .glyphs,
        )
    }

    @Test
    fun aCoupledResizeScalesHeightAndAscentByThePixelHeight() {
        png(star, 16)

        val written = GlyphEdits.resize(pack, "star", 8, coupled = true)

        assertEquals(listOf(allocation), written)
        assertEquals(listOf(16, 12), glyph("star").let { listOf(it.height, it.ascent) })
        assertEquals(listOf(8, 6), glyph("moon").let { listOf(it.height, it.ascent) })
    }

    @Test
    fun aResizeBackToTheBlocksSizeDropsTheGlyphsOwnHeight() {
        png(star, 16)
        GlyphEdits.resize(pack, "star", 8, coupled = true)
        png(star, 8)

        GlyphEdits.resize(pack, "star", 16, coupled = true)

        val entry =
            (groovy.json.JsonSlurper().parse(allocation) as Map<*, *>).let { root ->
                (((root["fonts"] as List<*>)[0] as Map<*, *>)["blocks"] as List<*>).let {
                    ((it[0] as Map<*, *>)["glyphs"] as List<*>)[0] as Map<*, *>
                }
            }
        assertEquals(listOf("code", "constant", "name", "texture"), entry.keys.toList())
    }

    @Test
    fun aDecoupledResizeKeepsHeightAndAscent() {
        png(star, 16)
        val before = allocation.readText()

        assertEquals(emptyList<File>(), GlyphEdits.resize(pack, "star", 8, coupled = false))

        assertEquals(before, allocation.readText())
    }

    @Test
    fun aResizeRefusesASheetCell() {
        val error = assertThrows<IllegalArgumentException> { GlyphEdits.resize(pack, "spawn", 8, coupled = true) }

        assertEquals("spawn is a cell of the sheet Icons, which sets the size of every cell.", error.message)
    }

    @Test
    fun aProvisionalCodePointThatMainHasTakenMovesBehindMainsGlyphs() {
        allocation.writeText(glyphs(badge("FE000", "star", "BADGE_STAR"), badge("FE001", "moon"), badge("FE002", "comet")))
        val main = glyphs(badge("FE000", "star", "BADGE_STAR"), badge("FE001", "sun"), badge("FE002", "rain"))

        val moves = GlyphEdits.assignCodePoints(pack, main)

        assertEquals(listOf("minecraft:default FE001 FE003 MOON", "minecraft:default FE002 FE004 COMET"), moves)
        assertEquals(
            listOf(0xFE000, 0xFE003, 0xFE004),
            GlyphAllocation
                .read(allocation)
                .fonts[0]
                .blocks[0]
                .glyphs
                .map { it.codePoint },
        )
    }

    @Test
    fun aProvisionalCodePointThatIsStillFreeStays() {
        allocation.writeText(glyphs(badge("FE000", "star", "BADGE_STAR"), badge("FE001", "moon")))
        val before = allocation.readText()

        assertEquals(emptyList<String>(), GlyphEdits.assignCodePoints(pack, glyphs(badge("FE000", "star", "BADGE_STAR"))))

        assertEquals(before, allocation.readText())
    }

    @Test
    fun aNewBlockThatOverlapsABlockOfMainMovesWithItsGlyphs() {
        allocation.writeText(
            glyphs(badge("FE000", "star", "BADGE_STAR")).replace(
                """{ "range": "FE040-FE04F", "title": "Bars", "height": 5, "ascent": 5, "glyphs": [] }""",
                """{ "range": "FE040-FE04F", "title": "Bars", "height": 5, "ascent": 5, "glyphs": [] },
                   { "range": "FE050-FE05F", "title": "Hearts", "height": 5, "ascent": 5, "glyphs": [{ "code": "FE050", "constant": "HEART", "texture": "nordtal:heart.png" }] }""",
            ),
        )
        val main =
            glyphs(badge("FE000", "star", "BADGE_STAR")).replace(
                """{ "range": "FE040-FE04F", "title": "Bars", "height": 5, "ascent": 5, "glyphs": [] }""",
                """{ "range": "FE040-FE04F", "title": "Bars", "height": 5, "ascent": 5, "glyphs": [] },
                   { "range": "FE050-FE05F", "title": "Coins", "glyphs": [] }""",
            )

        val moves = GlyphEdits.assignCodePoints(pack, main)

        assertEquals(listOf("nordtal:hud FE050 FE010 HEART"), moves)
        val block = GlyphAllocation.read(allocation).fonts[1].blocks[0]
        assertEquals(listOf(0xFE010..0xFE01F, 0xFE010), listOf(block.range, block.glyphs[0].codePoint))
    }

    private fun icons(vararg cells: String) =
        glyphs(badge("FE000", "star", "BADGE_STAR"), badge("FE001", "moon")).replace(
            """[{ "code": "FE020", "constant": "SPAWN", "name": "spawn" }]""",
            "[" +
                cells.withIndex().joinToString(", ") { (index, name) ->
                    """{ "code": "FE02$index", "constant": "${name.uppercase()}", "name": "$name" }"""
                } + "]",
        )

    private fun cells() =
        GlyphAllocation
            .read(allocation)
            .fonts[0]
            .blocks[1]
            .glyphs
            .map { "%X %s".format(it.codePoint, it.constant) }

    @Test
    fun aRenamedSheetCellKeepsItsColumnAndCodePointWhenTheCodePointsAreAssigned() {
        allocation.writeText(icons("spawn", "home", "shop"))

        GlyphEdits.rename(pack, repository, "spawn", "base")
        val moves = GlyphEdits.assignCodePoints(pack, icons("spawn", "home", "shop"))

        assertEquals(emptyList<String>(), moves)
        assertEquals(listOf("FE020 BASE", "FE021 HOME", "FE022 SHOP"), cells())
    }

    @Test
    fun aBlockOfMainIsMatchedByItsRangeAndTitleNotItsTitleAlone() {
        val bars = """{ "range": "FE040-FE04F", "title": "Bars", "height": 5, "ascent": 5, "glyphs": [] }"""
        val heart = """{ "code": "%s", "constant": "HEART", "texture": "nordtal:heart.png" }"""
        allocation.writeText(
            glyphs(badge("FE000", "star", "BADGE_STAR")).replace(
                bars,
                """$bars, { "range": "FE050-FE05F", "title": "Hearts", "height": 5, "ascent": 5, "glyphs": [${heart.format("FE050")}] }""",
            ),
        )
        val main =
            glyphs(badge("FE000", "star", "BADGE_STAR")).replace(
                bars,
                """$bars, { "range": "FE050-FE05F", "title": "Coins", "glyphs": [] },
                   { "range": "FE060-FE06F", "title": "Hearts", "height": 5, "ascent": 5, "glyphs": [${heart.format("FE060")}] }""",
            )

        val moves = GlyphEdits.assignCodePoints(pack, main)

        assertEquals(listOf("nordtal:hud FE050 FE010 HEART"), moves)
    }

    @Test
    fun aUseInAFileThatGitDoesNotTrackYetRefusesTheRename() {
        repository
            .resolve("smp/src/main/java/Draft.java")
            .apply {
                parentFile.mkdirs()
            }.writeText("class Draft { String s = Glyphs.BADGE_STAR; }\n")

        val refusal = assertThrows<IllegalStateException> { GlyphEdits.rename(pack, repository, "star", "sun") }

        assertEquals("star is still used in 1 place:\n    smp/src/main/java/Draft.java:1", refusal.message)
    }

    @Test
    fun aDeleteOfASheetCellBeforeItsLastRefusesSinceTheLaterCellsWouldShiftColumns() {
        allocation.writeText(icons("spawn", "home", "shop"))
        val before = allocation.readText()

        val refusal = assertThrows<IllegalArgumentException> { GlyphEdits.delete(pack, repository, "home") }

        assertEquals(
            "home is cell 2 of 3 of the sheet Icons. Only its last cell can be deleted, since a later cell would move to another column.",
            refusal.message,
        )
        assertEquals(before, allocation.readText())
        GlyphEdits.delete(pack, repository, "shop")
        assertEquals(listOf("FE020 SPAWN", "FE021 HOME"), cells())
    }

    @Test
    fun aDeleteKeepsAPngThatASheetOrAnItemModelStillDraws() {
        allocation.writeText(
            glyphs(badge("FE000", "star", "BADGE_STAR"), badge("FE001", "moon"), badge("FE002", "icons"))
                .replace("\"texture\": \"nordtal:badges/icons.png\"", "\"texture\": \"nordtal:icons.png\""),
        )
        val icons = pack.resolve("src/assets/nordtal/textures/icons.png").also { png(it, 8) }
        pack.resolve("src/assets/nordtal/models/item/moon.json").apply { parentFile.mkdirs() }.writeText(
            """{ "parent": "minecraft:item/generated", "textures": { "layer0": "nordtal:badges/moon" } }""",
        )
        val moon = pack.resolve("src/assets/nordtal/textures/badges/moon.png")

        assertEquals(listOf(allocation), GlyphEdits.delete(pack, repository, "icons"))
        assertEquals(listOf(allocation), GlyphEdits.delete(pack, repository, "moon"))

        assertTrue(icons.exists())
        assertTrue(moon.exists())
    }

    @Test
    fun aDeleteThatCannotRemoveItsPngWritesNothing() {
        val before = allocation.readText()
        star.parentFile.setWritable(false)

        val refusal =
            try {
                assertThrows<IllegalStateException> { GlyphEdits.delete(pack, repository, "star") }
            } finally {
                star.parentFile.setWritable(true)
            }

        assertTrue("so nothing was written" in refusal.message!!, refusal.message)
        assertEquals(before, allocation.readText())
        assertTrue(star.exists())
        assertTrue(starArt.exists())
    }

    @Test
    fun aResizeRefusesAPngThatIsMissingOrNoPng() {
        star.delete()
        val missing = assertThrows<IllegalArgumentException> { GlyphEdits.resize(pack, "star", 8, coupled = true) }
        star.writeText("not a png")
        val broken = assertThrows<IllegalArgumentException> { GlyphEdits.resize(pack, "star", 8, coupled = true) }

        assertEquals("src/assets/nordtal/textures/badges/star.png is no PNG that can be read.", missing.message)
        assertEquals(missing.message, broken.message)
    }

    @Test
    fun anEditSetsTheStatusDisplayHeightAscentAndAboutOfAGlyph() {
        val written = GlyphEdits.edit(pack, "star", status = "final candidate", height = 9, ascent = 7, about = "A gold star.")

        assertEquals(listOf(allocation), written)
        val glyph = glyph("star")
        assertEquals(listOf("final candidate", 9, 7, "A gold star."), listOf(glyph.status, glyph.height, glyph.ascent, glyph.about))
        assertEquals(listOf(null, 8, 6, null), glyph("moon").let { listOf(it.status, it.height, it.ascent, it.about) })
    }

    @Test
    fun anEditLeavesWhatItIsNotGivenAndKeepsTheOrderOfTheKeys() {
        GlyphEdits.edit(pack, "star", about = "A star.")
        GlyphEdits.edit(pack, "star", height = 10)

        assertEquals(listOf("code", "constant", "name", "texture", "height", "about"), entry(0).keys.toList())
        assertEquals("A star.", glyph("star").about)
    }

    @Test
    fun anEditToTheBlocksValueOrToAnEmptyTextRemovesTheGlyphsOwnValue() {
        GlyphEdits.edit(pack, "star", status = "keep", height = 10, ascent = 9, about = "A star.")

        GlyphEdits.edit(pack, "star", status = "", height = 8, ascent = 6, about = "")

        assertEquals(listOf("code", "constant", "name", "texture"), entry(0).keys.toList())
    }

    @Test
    fun anEditRefusesAStatusThatGlyphsJsonForbidsAndWritesNothing() {
        val before = allocation.readText()

        val error = assertThrows<IllegalStateException> { GlyphEdits.edit(pack, "star", status = "done") }

        assertTrue(error.message!!.contains("'status' is one of keep, final candidate, placeholder, not 'done'"), error.message)
        assertEquals(before, allocation.readText())
    }

    @Test
    fun anEditRefusesTheDisplayHeightOrAscentOfASheetCellButSetsItsStatus() {
        val error = assertThrows<IllegalArgumentException> { GlyphEdits.edit(pack, "spawn", height = 9) }
        GlyphEdits.edit(pack, "spawn", status = "placeholder")

        assertEquals("spawn is a cell of the sheet Icons, which sets the display height and ascent of every cell.", error.message)
        assertEquals("placeholder", glyph("spawn").status)
    }

    private fun entry(index: Int): Map<*, *> =
        (groovy.json.JsonSlurper().parse(allocation) as Map<*, *>).let { root ->
            (((root["fonts"] as List<*>)[0] as Map<*, *>)["blocks"] as List<*>).let {
                ((it[0] as Map<*, *>)["glyphs"] as List<*>)[index] as Map<*, *>
            }
        }
}
