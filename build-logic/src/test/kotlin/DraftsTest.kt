package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DraftsTest {
    @TempDir
    lateinit var root: File

    private fun drafts(clone: DraftClone) = Drafts.list(clone.pack).map { listOf(it.id, it.label, it.state, it.files) }

    @Test
    fun aDraftIsOneTextureWithItsEntriesInGlyphsJson() {
        val clone = DraftClone(root, "star@FE000", "moon@FE001", "sun@FE002")
        clone.png("star", 0xFFFFFFFF.toInt())
        clone.writeGlyphs("star@FE000", "moon@FE001=HALF_MOON", "comet@FE003")
        clone.png("comet", 0xFFFFFFFF.toInt())
        clone.pngFile("sun").delete()

        assertEquals(
            listOf(
                listOf("nordtal:badges/comet.png", "comet", "added", listOf("src/assets/nordtal/textures/badges/comet.png")),
                listOf("nordtal:badges/moon.png", "moon", "changed", emptyList<String>()),
                listOf("nordtal:badges/star.png", "star", "changed", listOf("src/assets/nordtal/textures/badges/star.png")),
                listOf("nordtal:badges/sun.png", "sun", "deleted", listOf("src/assets/nordtal/textures/badges/sun.png")),
            ),
            drafts(clone),
        )
    }

    @Test
    fun theSourceAndTheMetadataOfATextureBelongToItsDraftAndATextOfNoAssetIsADraftOfItsOwn() {
        val clone = DraftClone(root, "star@FE000")
        clone.pack
            .resolve("art/assets/nordtal/textures/badges/star.aseprite")
            .apply { parentFile.mkdirs() }
            .writeText("art")
        clone.pack.resolve("src/assets/nordtal/textures/badges/star.png.mcmeta").writeText("{}")
        clone.pack
            .resolve("src/assets/minecraft/lang/en_us.json")
            .apply { parentFile.mkdirs() }
            .writeText("""{ "menu.title": "Pause" }""")

        assertEquals(
            listOf(
                listOf(
                    "nordtal:badges/star.png",
                    "star",
                    "changed",
                    listOf(
                        "art/assets/nordtal/textures/badges/star.aseprite",
                        "src/assets/nordtal/textures/badges/star.png.mcmeta",
                    ),
                ),
                listOf("text menu.title", "menu.title", "added", emptyList<String>()),
            ),
            drafts(clone),
        )
    }

    @Test
    fun aDiscardRestoresTheFilesAndEntriesOfOneDraftAndKeepsTheOthers() {
        val clone = DraftClone(root, "star@FE000", "moon@FE001")
        val committed = clone.pack.resolve("glyphs.json").readText()
        clone.png("star", 0xFFFFFFFF.toInt())
        clone.writeGlyphs("star@FE000=BIG_STAR", "moon@FE001=HALF_MOON", "comet@FE002")
        clone.png("comet", 0xFFFFFFFF.toInt())

        Drafts.discard(clone.pack, "nordtal:badges/star.png")
        Drafts.discard(clone.pack, "nordtal:badges/comet.png")

        assertEquals(listOf("nordtal:badges/moon.png"), Drafts.list(clone.pack).map { it.id })
        assertFalse(clone.pngFile("comet").exists())
        assertEquals(committed.replace("\"MOON\"", "\"HALF_MOON\""), clone.pack.resolve("glyphs.json").readText())
    }

    @Test
    fun aDiscardOfADraftInReviewGoesBackToItsPullRequest() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())
        Publish(clone.clone, clone.pack, clone.gh.absolutePath) {
        }.publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
        val published = clone.git(clone.clone, "hash-object", "--", clone.pngFile("star").absolutePath)
        clone.png("star", 0xFF00FF00.toInt())

        Drafts.discard(clone.pack, "nordtal:badges/star.png")

        assertEquals(published, clone.git(clone.clone, "hash-object", "--", clone.pngFile("star").absolutePath))
        assertEquals(emptyList<String>(), Drafts.list(clone.pack).map { it.id })
    }

    @Test
    fun aTextOfAnAssetIsPartOfItsDraftAndADiscardRestoresItAlone() {
        val clone = DraftClone(root, "star@FE000")
        val stone = clone.pack.resolve("src/assets/nordtal/textures/block/stone.png")
        clone.pngFile("star").copyTo(stone.apply { parentFile.mkdirs() })
        val texts = clone.pack.resolve("src/assets/nordtal/lang/en_us.json").apply { parentFile.mkdirs() }
        texts.writeText("""{ "block.nordtal.stone": "Stone", "block.nordtal.stone.lore": "Grey" }""")
        clone.commitAll("feat: stone")
        texts.writeText("""{ "block.nordtal.stone": "Granite", "block.nordtal.stone.lore": "Grey", "menu.title": "Pause" }""")

        val listed = Drafts.list(clone.pack)

        assertEquals(
            listOf(
                listOf("nordtal:block/stone.png", "stone", "changed", emptyList<String>(), listOf("block.nordtal.stone")),
                listOf("text menu.title", "menu.title", "added", emptyList<String>(), listOf("menu.title")),
            ),
            listed.map { listOf(it.id, it.label, it.state, it.files, it.keys) },
        )

        Drafts.discard(clone.pack, "nordtal:block/stone.png")

        assertEquals(listOf("text menu.title"), Drafts.list(clone.pack).map { it.id })
        assertEquals(
            mapOf("block.nordtal.stone" to "Stone", "block.nordtal.stone.lore" to "Grey", "menu.title" to "Pause"),
            Texts.work(clone.pack).values.single(),
        )
    }

    @Test
    fun aDeletedItemIsOneDraftWithItsModelAndTexture() {
        val clone = DraftClone(root, "star@FE000")
        val assets = clone.pack.resolve("src/assets/nordtal")
        val wand = assets.resolve("textures/item/wand.png")
        clone.pngFile("star").copyTo(wand.apply { parentFile.mkdirs() })
        assets.resolve("models/item/wand.json").apply { parentFile.mkdirs() }.writeText(
            """{ "parent": "minecraft:item/generated", "textures": { "layer0": "nordtal:item/wand" } }""",
        )
        assets.resolve("items/wand.json").apply { parentFile.mkdirs() }.writeText(
            """{ "model": { "type": "minecraft:model", "model": "nordtal:item/wand" } }""",
        )
        clone.commitAll("feat: wand")
        clone.git(clone.clone, "push", "-q", "origin", "main")

        ItemFiles(clone.pack) { error("no client jar") }.use { it.deleteItem("nordtal:wand", clone.clone) }

        assertEquals(
            listOf(
                listOf(
                    "nordtal:item/wand.png",
                    "wand",
                    "deleted",
                    listOf(
                        "src/assets/nordtal/items/wand.json",
                        "src/assets/nordtal/models/item/wand.json",
                        "src/assets/nordtal/textures/item/wand.png",
                    ),
                ),
            ),
            drafts(clone),
        )
    }

    @Test
    fun aDeletedItemIsOneDraftWithItsParentModelAndTheParentsTexture() {
        val clone = DraftClone(root, "star@FE000")
        val assets = clone.pack.resolve("src/assets/nordtal")
        for (texture in listOf("wand", "grip")) {
            clone.pngFile("star").copyTo(assets.resolve("textures/item/$texture.png").apply { parentFile.mkdirs() })
        }
        assets.resolve("models/item/handheld.json").apply { parentFile.mkdirs() }.writeText(
            """{ "parent": "minecraft:item/generated", "textures": { "layer0": "nordtal:item/grip" } }""",
        )
        assets.resolve("models/item/wand.json").writeText(
            """{ "parent": "nordtal:item/handheld", "textures": { "layer0": "nordtal:item/wand" } }""",
        )
        assets.resolve("items/wand.json").apply { parentFile.mkdirs() }.writeText(
            """
            { "model": { "type": "minecraft:select", "property": "minecraft:main_hand", "cases": [],
              "fallback": { "type": "minecraft:model", "model": "nordtal:item/wand" } } }
            """.trimIndent(),
        )
        clone.commitAll("feat: wand")
        clone.git(clone.clone, "push", "-q", "origin", "main")

        ItemFiles(clone.pack) { error("no client jar") }.use { it.deleteItem("nordtal:wand", clone.clone) }

        assertEquals(
            listOf(
                listOf(
                    "src/assets/nordtal/items/wand.json",
                    "wand.json",
                    "deleted",
                    listOf(
                        "src/assets/nordtal/items/wand.json",
                        "src/assets/nordtal/models/item/handheld.json",
                        "src/assets/nordtal/models/item/wand.json",
                        "src/assets/nordtal/textures/item/grip.png",
                        "src/assets/nordtal/textures/item/wand.png",
                    ),
                ),
            ),
            drafts(clone),
        )
    }

    @Test
    fun aDeletedItemWhoseTextureStaysIsADraftOfItsOwn() {
        val clone = DraftClone(root, "star@FE000")
        val assets = clone.pack.resolve("src/assets/nordtal")
        clone.pngFile("star").copyTo(assets.resolve("textures/item/shared.png").apply { parentFile.mkdirs() })
        for (item in listOf("wand", "rod")) {
            assets.resolve("models/item/$item.json").apply { parentFile.mkdirs() }.writeText(
                """{ "parent": "minecraft:item/generated", "textures": { "layer0": "nordtal:item/shared" } }""",
            )
            assets.resolve("items/$item.json").apply { parentFile.mkdirs() }.writeText(
                """{ "model": { "type": "minecraft:model", "model": "nordtal:item/$item" } }""",
            )
        }
        clone.commitAll("feat: wand and rod")
        clone.git(clone.clone, "push", "-q", "origin", "main")

        ItemFiles(clone.pack) { error("no client jar") }.use { it.deleteItem("nordtal:wand", clone.clone) }

        assertEquals(
            listOf(
                listOf(
                    "src/assets/nordtal/items/wand.json",
                    "wand.json",
                    "deleted",
                    listOf("src/assets/nordtal/items/wand.json", "src/assets/nordtal/models/item/wand.json"),
                ),
            ),
            drafts(clone),
        )
    }

    @Test
    fun deletedItemsThatDrewTheSameDeletedTextureAreOneDraft() {
        val clone = DraftClone(root, "star@FE000")
        val assets = clone.pack.resolve("src/assets/nordtal")
        clone.pngFile("star").copyTo(assets.resolve("textures/item/shared.png").apply { parentFile.mkdirs() })
        for (item in listOf("a", "b")) {
            clone.pngFile("star").copyTo(assets.resolve("textures/item/$item.png"))
            assets.resolve("models/item/$item.json").apply { parentFile.mkdirs() }.writeText(
                """{ "parent": "minecraft:item/generated", "textures": """ +
                    """{ "layer0": "nordtal:item/$item", "layer1": "nordtal:item/shared" } }""",
            )
            assets.resolve("items/$item.json").apply { parentFile.mkdirs() }.writeText(
                """{ "model": { "type": "minecraft:model", "model": "nordtal:item/$item" } }""",
            )
        }
        clone.commitAll("feat: a and b")
        clone.git(clone.clone, "push", "-q", "origin", "main")

        ItemFiles(clone.pack) { error("no client jar") }.use { files ->
            files.deleteItem("nordtal:a", clone.clone)
            files.deleteItem("nordtal:b", clone.clone)
        }

        assertEquals(
            listOf(
                listOf(
                    "nordtal:item/a.png",
                    "a",
                    "deleted",
                    listOf(
                        "src/assets/nordtal/items/a.json",
                        "src/assets/nordtal/items/b.json",
                        "src/assets/nordtal/models/item/a.json",
                        "src/assets/nordtal/models/item/b.json",
                        "src/assets/nordtal/textures/item/a.png",
                        "src/assets/nordtal/textures/item/b.png",
                        "src/assets/nordtal/textures/item/shared.png",
                    ),
                ),
            ),
            drafts(clone),
        )
    }
}
