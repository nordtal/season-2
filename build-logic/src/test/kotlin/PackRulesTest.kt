package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PackRulesTest {
    @TempDir
    lateinit var root: File

    @Test
    fun anAsepriteFileWithoutItsPngIsRefused() {
        file("art/assets/nordtal/textures/badges/star.aseprite").writeText("")

        assertEquals(
            listOf(
                "art/assets/nordtal/textures/badges/star.aseprite has no PNG at assets/nordtal/textures/badges/star.png. " +
                    "Export the PNG from it.",
            ),
            PackRules.artFindings(root.resolve("art"), root.resolve("pack/assets")),
        )
    }

    @Test
    fun anAsepriteFileBesideItsPngPasses() {
        file("art/assets/nordtal/textures/badges/star.aseprite").writeText("")
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", file("pack/assets/nordtal/textures/badges/star.png"))

        assertEquals(emptyList<String>(), PackRules.artFindings(root.resolve("art"), root.resolve("pack/assets")))
    }

    @Test
    fun aMissingArtFolderHasNoFindings() {
        assertEquals(emptyList<String>(), PackRules.artFindings(root.resolve("art"), root.resolve("pack/assets")))
    }

    @Test
    fun anItemDefinitionIsCheckedByItsRulesAndByItsReferencesIntoThePack() {
        file("pack/assets/nordtal/items/lamp.json").writeText("""{ "model": { "type": "minecraft:cube" } }""")
        file(
            "pack/assets/nordtal/items/wand.json",
        ).writeText("""{ "model": { "type": "minecraft:model", "model": "nordtal:item/wand" } }""")
        file(
            "pack/assets/nordtal/models/item/wand.json",
        ).writeText("""{ "parent": "nordtal:item/handheld", "textures": { "layer0": "nordtal:item/wand" } }""")
        file(
            "pack/assets/nordtal/items/apple.json",
        ).writeText("""{ "model": { "type": "minecraft:model", "model": "minecraft:item/apple" } }""")
        file("pack/assets/nordtal/items/broken.json").writeText("""{ "model": """)

        assertEquals(
            listOf(
                "nordtal/items/broken.json is no JSON. Export or write it again.",
                "nordtal/items/lamp.json breaks a rule of item definitions: model has no known 'type': minecraft:cube",
                "nordtal/items/wand.json breaks a rule of item definitions: model nordtal:item/handheld is in neither the pack nor the client jar",
                "nordtal/items/wand.json breaks a rule of item definitions: texture nordtal:item/wand of model nordtal:item/wand is in neither the pack nor the client jar",
            ),
            PackRules.findings(root.resolve("pack/assets")),
        )
    }

    private fun file(path: String): File = root.resolve(path).apply { parentFile.mkdirs() }
}
