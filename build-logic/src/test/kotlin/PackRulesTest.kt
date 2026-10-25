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

    private fun file(path: String): File = root.resolve(path).apply { parentFile.mkdirs() }
}
