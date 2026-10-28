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
}
