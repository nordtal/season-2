package eu.nordtal.season.build

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class VanillaClientTest {
    @TempDir
    lateinit var directory: File

    private val jar =
        zip(
            "assets/minecraft/textures/block/stone.png" to png(16, 16),
            "assets/minecraft/textures/block/water_still.png" to png(16, 512),
            "assets/minecraft/textures/block/water_still.png.mcmeta" to """{"animation": {}}""".toByteArray(),
            "assets/minecraft/models/block/stone.json" to "{}".toByteArray(),
            "assets/minecraft/items/stone.json" to "{}".toByteArray(),
            "assets/minecraft/items/apple.json" to "{}".toByteArray(),
            "net/minecraft/Main.class" to ByteArray(4),
        )

    @Test
    fun theTexturesAreListedWithTheirPixelsAndAnimationAndTheItemDefinitionsByTheirIds() {
        val file = directory.resolve("client.jar").apply { writeBytes(jar) }

        assertEquals(
            mapOf(
                "textures" to
                    listOf(
                        mapOf("id" to "minecraft:block/stone.png", "width" to 16, "height" to 16, "mcmeta" to false),
                        mapOf("id" to "minecraft:block/water_still.png", "width" to 16, "height" to 512, "mcmeta" to true),
                    ),
                "items" to listOf("minecraft:apple", "minecraft:stone"),
            ),
            VanillaClient.assets(file),
        )
    }

    @Test
    fun aTextureIsCopiedWithItsMcmetaToTheOverridePath() {
        val file = directory.resolve("client.jar").apply { writeBytes(jar) }
        val assets = directory.resolve("pack/src/assets")

        val copied = VanillaClient.copy(file, "minecraft:block/water_still.png", assets)

        val png = assets.resolve("minecraft/textures/block/water_still.png")
        assertEquals(listOf(png, File(png.path + ".mcmeta")), copied)
        assertEquals(512, ImageIO.read(png).height)
        assertEquals("""{"animation": {}}""", File(png.path + ".mcmeta").readText())
    }

    @Test
    fun aCopyRefusesATextureTheJarLacksAndAnExistingOverride() {
        val file = directory.resolve("client.jar").apply { writeBytes(jar) }
        val assets = directory.resolve("pack/src/assets")
        VanillaClient.copy(file, "minecraft:block/stone.png", assets)

        assertThrows(IllegalArgumentException::class.java) { VanillaClient.copy(file, "minecraft:block/nothing.png", assets) }
        assertThrows(IllegalArgumentException::class.java) { VanillaClient.copy(file, "minecraft:../../stone.png", assets) }
        assertThrows(IllegalArgumentException::class.java) { VanillaClient.copy(file, "minecraft:Block/stone.png", assets) }
        assertThrows(IllegalArgumentException::class.java) { VanillaClient.copy(file, "block/stone.png", assets) }
        assertThrows(IllegalStateException::class.java) { VanillaClient.copy(file, "minecraft:block/stone.png", assets) }
    }

    @Test
    fun theListingReadsTheClientJarItIsGivenAndPrintsItsPathOnARunThatIsUpToDate() {
        // The build-logic classes, which the build below loads as its own.
        val classes =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .filter { "build-logic" in it && "/build/" in it }
                .joinToString(", ") { "'" + it.replace("\\", "/") + "'" }
        val project = directory.resolve("project").apply { mkdirs() }
        project.resolve("settings.gradle").writeText("")
        project.resolve("client.jar").writeBytes(jar)
        project.resolve("build.gradle").writeText(
            """
            buildscript { dependencies { classpath files($classes) } }
            tasks.register('list', eu.nordtal.season.build.ListVanillaAssets) {
                version = '26.2'
                client.from('client.jar')
                listing = layout.buildDirectory.file('listing.json')
            }
            """.trimIndent(),
        )
        val runner = GradleRunner.create().withProjectDir(project).withArguments("list", "--quiet")

        runner.build()
        val second = runner.build().output

        assertTrue(project.resolve("build/listing.json").absolutePath in second, second)
        val listing = project.resolve("build/listing.json").readText()
        assertTrue("minecraft:block/water_still.png" in listing, listing)
        assertTrue("\"minecraft:apple\"" in listing, listing)
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteArrayOutputStream()
            .also {
                ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
