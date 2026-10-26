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
    fun theSourceAndTheMetadataOfATextureBelongToItsDraftAndAnotherFileIsADraftOfItsOwn() {
        val clone = DraftClone(root, "star@FE000")
        clone.pack
            .resolve("art/assets/nordtal/textures/badges/star.aseprite")
            .apply { parentFile.mkdirs() }
            .writeText("art")
        clone.pack.resolve("src/assets/nordtal/textures/badges/star.png.mcmeta").writeText("{}")
        clone.pack
            .resolve("src/assets/minecraft/lang/en_us.json")
            .apply { parentFile.mkdirs() }
            .writeText("{}")

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
                listOf("src/assets/minecraft/lang/en_us.json", "en_us.json", "added", listOf("src/assets/minecraft/lang/en_us.json")),
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
}
