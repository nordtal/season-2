package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PackModelTest {
    @TempDir
    lateinit var pack: File

    @Test
    fun aGlyphNamesItsFilesPixelSizeAndTheValuesThePanelEdits() {
        allocation(
            """
            {"fonts": [{"font": "minecraft:default", "blocks": [{"range": "FE000-FE00F", "title": "Badges", "glyphs": [
              {"code": "FE001", "constant": "STAR", "name": "star", "texture": "nordtal:badges/star.png",
               "height": 8, "ascent": 7, "status": "keep", "about": "A star."}]}]}]}
            """,
        )
        png("src/assets/nordtal/textures/badges/star.png", 9, 7)
        file("art/assets/nordtal/textures/badges/star.aseprite").writeText("")

        val glyph = only(only(only(fonts()).list("blocks")).list("glyphs"))

        assertEquals("FE001", glyph["code"])
        assertEquals("STAR", glyph["constant"])
        assertEquals("star", glyph["name"])
        assertEquals("keep", glyph["status"])
        assertEquals(8, glyph["height"])
        assertEquals(7, glyph["ascent"])
        assertEquals("A star.", glyph["about"])
        val texture = glyph.map("texture")
        assertEquals("nordtal:badges/star.png", texture["id"])
        assertEquals("src/assets/nordtal/textures/badges/star.png", texture["png"])
        assertEquals("art/assets/nordtal/textures/badges/star.aseprite", texture["aseprite"])
        assertEquals(true, texture["asepriteExists"])
        assertEquals(9, texture["width"])
        assertEquals(7, texture["height"])
    }

    @Test
    fun aSheetIsOneEntryWithItsCells() {
        allocation(
            """
            {"fonts": [{"font": "nordtal:gui_r{row}", "rows": 2, "pitch": 18, "constant": "ROWS", "blocks": [
              {"range": "FE110-FE11F", "title": "Icons", "texture": "nordtal:icons.png", "height": 8, "ascent": -9,
               "glyphs": [{"code": "FE110", "constant": "SPAWN"}, {"code": "FE111", "constant": "DEATH", "status": "placeholder"}]},
              {"title": "Text", "texture": "nordtal:text.png", "height": 5, "ascent": -10, "chars": ["AB", "C\u0000"]}]}]}
            """,
        )
        png("src/assets/nordtal/textures/icons.png", 16, 8)
        png("src/assets/nordtal/textures/text.png", 10, 10)

        val font = only(fonts())
        assertEquals(2, font["rows"])
        val (icons, text) = font.list("blocks")

        assertEquals("sheet", icons["kind"])
        assertEquals(8, icons["height"])
        assertEquals(16, icons.map("texture")["width"])
        assertEquals(
            listOf(listOf(0, 0, "FE110", "SPAWN", null), listOf(0, 1, "FE111", "DEATH", "placeholder")),
            icons.list("cells").map { listOf(it["row"], it["column"], it["code"], it["constant"], it["status"]) },
        )
        assertEquals(
            listOf(listOf(0, 0, "41"), listOf(0, 1, "42"), listOf(1, 0, "43")),
            text.list("cells").map { listOf(it["row"], it["column"], it["code"]) },
        )
        assertFalse("glyphs" in icons)
    }

    @Test
    fun aGlyphHoldsOnlyTheStatusItSetsAndTheBlockHoldsItsOwn() {
        allocation(
            """
            {"fonts": [{"font": "minecraft:default", "blocks": [{"range": "FE000-FE00F", "title": "Badges", "status": "placeholder",
              "height": 8, "ascent": 7, "glyphs": [
              {"code": "FE001", "constant": "STAR", "name": "star", "texture": "nordtal:star.png"},
              {"code": "FE002", "constant": "MOON", "name": "moon", "texture": "nordtal:moon.png", "status": "keep"}]}]}]}
            """,
        )

        val block = only(only(fonts()).list("blocks"))

        assertEquals("placeholder", block["status"])
        assertEquals(listOf(null, "keep"), block.list("glyphs").map { it["status"] })
        assertEquals(listOf("placeholder", "keep"), block.list("glyphs").map { it["effectiveStatus"] })
    }

    @Test
    fun aCellNamesTheStatusItShowsAndTheHeightAndAscentOfItsBlock() {
        allocation(
            """
            {"fonts": [{"font": "nordtal:bossbar", "blocks": [
              {"range": "FE110-FE11F", "title": "Icons", "texture": "nordtal:icons.png", "height": 8, "ascent": -9,
               "status": "keep", "glyphs": [{"code": "FE110", "constant": "SPAWN"}, {"code": "FE111", "constant": "DEATH", "status": "placeholder"}]},
              {"title": "Text", "texture": "nordtal:text.png", "height": 5, "ascent": -10, "chars": ["A"]}]}]}
            """,
        )

        val (icons, text) = only(fonts()).list("blocks")

        assertEquals(
            listOf(listOf(null, "keep", 8, -9), listOf("placeholder", "placeholder", 8, -9)),
            icons.list("cells").map { listOf(it["status"], it["effectiveStatus"], it["height"], it["ascent"]) },
        )
        assertEquals(listOf(5, -10), only(text.list("cells")).let { listOf(it["height"], it["ascent"]) })
    }

    @Test
    fun overridesAreGroupedByFolderWithAnimationAndScaling() {
        allocation("""{"fonts": [{"font": "minecraft:default", "blocks": []}]}""")
        png("src/assets/minecraft/textures/gui/sprites/boss_bar/white_progress.png", 182, 5)
        png("src/assets/minecraft/textures/block/water_still.png", 16, 64)
        file("src/assets/minecraft/textures/block/water_still.png.mcmeta")
            .writeText("""{"animation": {"frametime": 2}}""")
        png("src/assets/minecraft/textures/gui/sprites/widget/button.png", 200, 20)
        file("src/assets/minecraft/textures/gui/sprites/widget/button.png.mcmeta")
            .writeText("""{"gui": {"scaling": {"type": "nine_slice", "width": 200, "height": 20, "border": 3}}}""")

        val overrides = model().list("overrides")

        assertEquals(listOf("block", "gui/sprites/boss_bar", "gui/sprites/widget"), overrides.map { it["category"] })
        val water = only(overrides[0].list("entries"))
        assertEquals("minecraft:block/water_still.png", water.map("texture")["id"])
        assertEquals("src/assets/minecraft/textures/block/water_still.png.mcmeta", water["mcmeta"])
        assertEquals(mapOf("frametime" to 2), water["animation"])
        assertEquals(null, water["scaling"])
        assertEquals(false, water["guiSprite"])
        assertEquals(
            (0 until 4).map { mapOf("x" to 0, "y" to 16 * it, "width" to 16, "height" to 16, "duration" to 100) },
            water["frames"],
        )
        val bar = only(overrides[1].list("entries"))
        assertEquals(null, bar["mcmeta"])
        assertEquals(182, bar.map("texture")["width"])
        assertEquals(true, bar["guiSprite"])
        assertEquals(null, bar["frames"])
        val button = only(overrides[2].list("entries"))
        assertEquals("nine_slice", button.map("scaling")["type"])
    }

    @Test
    fun theModelListsTheStatusesAGlyphCanHave() {
        allocation("""{"fonts": [{"font": "minecraft:default", "blocks": []}]}""")

        assertEquals(listOf("keep", "final candidate", "placeholder"), model()["statuses"])
    }

    @Test
    fun theModelListsTheScalingsAGuiSpriteCanHave() {
        allocation("""{"fonts": [{"font": "minecraft:default", "blocks": []}]}""")

        assertEquals(listOf("stretch", "tile", "nine_slice"), model()["scalings"])
    }

    @Test
    fun itemsAreGroupedByNamespaceAndLanguageFilesAreLeftOut() {
        allocation("""{"fonts": [{"font": "minecraft:default", "blocks": []}]}""")
        file("src/assets/nordtal/items/blank.json").writeText("""{"model": {"type": "minecraft:empty"}}""")
        file("src/assets/minecraft/items/stick.json")
            .writeText("""{"model": {"type": "minecraft:model", "model": "nordtal:item/wand"}}""")
        file("src/assets/nordtal/models/item/wand.json")
            .writeText("""{"parent": "minecraft:item/handheld", "textures": {"layer0": "nordtal:item/wand"}}""")
        png("src/assets/nordtal/textures/item/wand.png", 16, 32)
        file("src/assets/minecraft/lang/en_us.json").writeText("{}")

        val model = model()
        val items = model.list("items")

        assertEquals(listOf("minecraft", "nordtal"), items.map { it["category"] })
        val blank = only(items[1].list("entries"))
        assertEquals("nordtal:blank", blank["id"])
        assertEquals("src/assets/nordtal/items/blank.json", blank["file"])
        assertEquals("minecraft:empty", blank["model"])
        assertEquals(null, blank["texture"])
        val stick = only(items[0].list("entries")).map("texture")
        assertEquals("nordtal:item/wand.png", stick["id"])
        assertEquals(listOf(16, 32), listOf(stick["width"], stick["height"]))
        assertTrue(model.list("overrides").isEmpty())
    }

    @Test
    fun anItemTextureThatIsNoIdentifierIsLeftOut() {
        allocation("""{"fonts": [{"font": "minecraft:default", "blocks": []}]}""")
        for ((name, layer) in listOf("up" to "nordtal:../../../../Library/foo", "absolute" to "/tmp/foo")) {
            file("src/assets/nordtal/items/$name.json")
                .writeText("""{"model": {"type": "minecraft:model", "model": "nordtal:item/$name"}}""")
            file("src/assets/nordtal/models/item/$name.json").writeText("""{"textures": {"layer0": "$layer"}}""")
        }

        val entries = model().list("items").flatMap { it.list("entries") }

        assertEquals(listOf("nordtal:absolute" to null, "nordtal:up" to null), entries.map { it["id"] to it["texture"] })
    }

    @Test
    fun theRealPackListsEveryGlyphAndSheetOfGlyphsJson() {
        val root = File(System.getProperty("nordtal.resourcePack"))
        val allocation = GlyphAllocation.read(root.resolve("glyphs.json"))

        val model = PackModel.of(allocation, root)

        val fonts = model.list("fonts")
        assertEquals(allocation.fonts.map { it.id }, fonts.map { it["font"] })
        val blocks = fonts.flatMap { it.list("blocks") }
        val listed =
            blocks.flatMap { block -> if (block["kind"] == "sheet") block.list("cells") else block.list("glyphs") }
        val drawn =
            allocation.fonts.flatMap { font ->
                font.blocks.flatMap { block ->
                    block.glyphs.map { "%X".format(it.codePoint) } +
                        block.chars.orEmpty().flatMap { row ->
                            row
                                .codePoints()
                                .toArray()
                                .filter { it != 0 }
                                .map { "%X".format(it) }
                        }
                }
            }
        assertEquals(drawn, listed.map { it["code"] })
        val textures = blocks.mapNotNull { it["texture"] as Map<*, *>? } + listed.mapNotNull { it["texture"] as Map<*, *>? }
        textures.forEach { assertTrue((it["width"] as Int) > 0, "${it["png"]} has a pixel size") }
        val overrides = model.list("overrides").flatMap { it.list("entries") }.map { it.map("texture")["id"] }
        assertTrue("minecraft:gui/sprites/boss_bar/white_progress.png" in overrides)
        assertEquals(listOf("nordtal:blank"), model.list("items").flatMap { it.list("entries") }.map { it["id"] })
    }

    @Test
    fun theModelMarksEveryDraftAndListsTheDraftsWithTheirPullRequests() {
        val clone = DraftClone(pack, "star@FE000", "moon@FE001", "sun@FE002")
        clone.png("star", 0xFFFFFFFF.toInt())
        clone.writeGlyphs("star@FE000", "moon@FE001=HALF_MOON", "sun@FE002")
        clone.pack.resolve("glyphs.json").apply { writeText(readText().replace("\"Badges\"", "\"Sky\"")) }
        clone.pack.resolve("src/assets/minecraft/lang/en_us.json").apply { parentFile.mkdirs() }.writeText("{}")

        val model =
            PackModel.of(GlyphAllocation.read(clone.pack.resolve("glyphs.json")), clone.pack, Drafts.list(clone.pack), Drafts.reviews(clone.pack))
        val font = only(model.list("fonts"))
        val block = only(font.list("blocks"))

        assertEquals(false, font["draft"])
        assertEquals(true, block["draft"])
        assertEquals(listOf(true, true, false), block.list("glyphs").map { it.map("texture")["draft"] })
        assertEquals(
            listOf(
                listOf("block minecraft:default FE000-FE00F", "Sky", "changed", null),
                listOf("nordtal:badges/moon.png", "moon", "changed", null),
                listOf("nordtal:badges/star.png", "star", "changed", null),
                listOf("src/assets/minecraft/lang/en_us.json", "en_us.json", "added", null),
            ),
            model.list("drafts").map { listOf(it["id"], it["label"], it["state"], it["review"]) },
        )
        assertEquals(emptyList<Any>(), model["reviews"])
    }

    private fun model(): Map<String, Any?> = PackModel.of(GlyphAllocation.read(pack.resolve("glyphs.json")), pack)

    private fun fonts() = model().list("fonts")

    private fun allocation(json: String) = file("glyphs.json").writeText(json.trimIndent())

    private fun file(path: String): File = pack.resolve(path).apply { parentFile.mkdirs() }

    private fun png(
        path: String,
        width: Int,
        height: Int,
    ) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, -1)
        ImageIO.write(image, "png", file(path))
    }

    private fun <T> only(items: List<T>): T = items.single()

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.list(key: String): List<Map<String, Any?>> = this[key] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.map(key: String): Map<String, Any?> = this[key] as Map<String, Any?>
}
