package eu.nordtal.season.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class ModelFilesTest {
    @TempDir
    lateinit var directory: File

    private val pack get() = directory.resolve("pack")

    private val assets get() = pack.resolve("src/assets")

    private fun png(
        width: Int,
        height: Int,
        rgb: Int = 0,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A client jar with the vanilla blocks that the station rail draws */
    private fun jar(): File =
        directory.resolve("client.jar").also { file ->
            if (file.isFile) return@also
            ZipOutputStream(file.outputStream()).use { zip ->
                mapOf(
                    "assets/minecraft/models/block/cube.json" to """{ "textures": { "particle": "#all" } }""".toByteArray(),
                    "assets/minecraft/textures/block/gold_block.png" to png(16, 16, 0xFFFFD700.toInt()),
                    "assets/minecraft/textures/block/lapis_block.png" to png(16, 16, 0xFF1F3F9F.toInt()),
                    "assets/minecraft/textures/block/stone.png" to png(16, 16, 0xFF808080.toInt()),
                ).forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    private fun files() = ItemFiles(pack, ::jar)

    private val rail = "nordtal:item/station_rail"

    private val model get() = assets.resolve("nordtal/models/item/station_rail.json")

    /** The station rail as the pack holds it, drawn by the item `nordtal:station_rail` */
    private fun station(textures: String = STATION_TEXTURES): File {
        model.parentFile.mkdirs()
        model.writeText(
            """{ "parent": "block/cube", "textures": { $textures },
                 "elements": [
                   { "from": [0, 0, 0], "to": [16, 1, 16], "faces": {
                     "up": { "uv": [0, 0, 16, 16], "texture": "#plate" },
                     "down": { "uv": [0, 0, 16, 16], "texture": "#plate", "cullface": "down" } } },
                   { "from": [2, 1, 0], "to": [4, 2, 16], "rotation": { "angle": 22.5, "axis": "y", "origin": [8, 8, 8] }, "faces": {
                     "up": { "uv": [0, 0, 2, 16], "rotation": 90, "texture": "#bar", "tintindex": 0 } } }
                 ] }""",
        )
        files().use { it.write("nordtal:station_rail", """{ "model": { "type": "minecraft:model", "model": "$rail" } }""") }
        return model
    }

    private fun edited(
        key: String,
        texture: String? = null,
        to: String? = null,
        delete: Boolean = false,
        model: String = rail,
        add: Boolean = false,
    ) = files().use { ModelFiles(pack, it).editTexture(model, key, texture, to, delete, 16, 16, add) }

    private fun textures() = (OxfmtJson.read(model) as Map<*, *>)["textures"] as Map<*, *>

    @Test
    fun aKeyIsSetToATextureOfTheClientJarByItsShortId() {
        station()

        assertEquals(listOf(model), edited("bar", "block/stone.png"))

        assertEquals("minecraft:block/stone", textures()["bar"])
    }

    @Test
    fun aKeyThatNamesANewTextureOfThePackGetsATransparentPngInTheSameRun() {
        station()

        val written = edited("plate", "nordtal:block/station_rail_plate")

        val png = assets.resolve("nordtal/textures/block/station_rail_plate.png")
        assertEquals(setOf(png, model), written.toSet())
        assertEquals("nordtal:block/station_rail_plate", textures()["plate"])
        ImageIO.read(png).let { assertEquals(listOf(16, 16, 0), listOf(it.width, it.height, it.getRGB(3, 3))) }
    }

    @Test
    fun aMissingTextureOfMinecraftIsRefused() {
        station()

        val refusal = assertThrows<FieldRefusal> { edited("bar", "block/emerald_block") }

        assertEquals("texture", refusal.field)
        assertEquals("minecraft:block/gold_block", textures()["bar"])
    }

    @Test
    fun aKeyIsAddedAndCanNameAnotherKey() {
        station()

        edited("rim", "#bar")

        assertEquals("#bar", textures()["rim"])
        assertEquals("texture", assertThrows<FieldRefusal> { edited("edge", "#nothing") }.field)
        assertEquals("key", assertThrows<FieldRefusal> { edited("bar", "block/stone", add = true) }.field)
        assertEquals("texture", assertThrows<FieldRefusal> { edited("bar", "#rim") }.field)
        assertEquals("minecraft:block/gold_block", textures()["bar"])
    }

    @Test
    fun aRenamedKeyIsRenamedInEveryFaceAndKeyThatDrawsIt() {
        station(""""particle": "#plate", "bar": "minecraft:block/gold_block", "plate": "minecraft:block/lapis_block"""")

        edited("plate", to = "deck")

        val json = OxfmtJson.read(model) as Map<*, *>
        assertEquals(
            mapOf("particle" to "#deck", "bar" to "minecraft:block/gold_block", "deck" to "minecraft:block/lapis_block"),
            json["textures"],
        )
        val faces =
            (json["elements"] as List<*>)
                .flatMap {
                    ((it as Map<*, *>)["faces"] as Map<*, *>).values
                }.map { (it as Map<*, *>)["texture"] }
        assertEquals(listOf("#deck", "#deck", "#bar"), faces)
        assertEquals("to", assertThrows<FieldRefusal> { edited("deck", to = "bar") }.field)
    }

    @Test
    fun aKeyThatFacesDrawIsNotRemovedButAnUnusedOneIs() {
        station()

        assertEquals("key", assertThrows<FieldRefusal> { edited("bar", delete = true) }.field)
        edited("particle", delete = true)

        assertEquals(setOf("bar", "plate"), textures().keys)
    }

    @Test
    fun aKeyOfAParentIsNeitherRenamedNorRemovedHere() {
        station()
        val child = assets.resolve("nordtal/models/item/child.json").apply { writeText("""{ "parent": "$rail" }""") }

        val refusal = assertThrows<FieldRefusal> { edited("bar", delete = true, model = "nordtal:item/child") }

        assertEquals("#bar comes from a parent of nordtal:item/child.", refusal.message)
        edited("bar", "block/stone", model = "nordtal:item/child")
        assertEquals(mapOf("bar" to "minecraft:block/stone"), (OxfmtJson.read(child) as Map<*, *>)["textures"])
    }

    @Test
    fun aModelOnlyInTheClientJarIsRefused() {
        val refusal = assertThrows<FieldRefusal> { edited("all", "block/stone", model = "minecraft:block/cube") }

        assertEquals("model", refusal.field)
    }

    private fun exported(to: File = directory.resolve("export/station_rail.bbmodel")): File =
        files().use {
            ModelFiles(pack, it).export(rail, to)
        }

    private fun imported(project: File) = files().use { it.import(project, rail, "nordtal:station_rail", "model") }

    @Test
    fun anExportedModelIsImportedOntoItsNodeAsTheSameModelWithItsReferencesKept() {
        val before = OxfmtJson.read(station())

        val written = imported(exported())

        assertEquals(listOf(model), written.filter { it.exists() && it.path.endsWith(".json") && "models" in it.path })
        assertEquals(before, OxfmtJson.read(model))
        assertEquals(listOf("models"), assets.resolve("nordtal").list()!!.filter { it != "items" })
    }

    /** The station rail in the layout of a file of the pack, its faces in an order and with keys in an order of its own */
    private fun laidOut(): String {
        station()
        model.writeText(
            """
            {
                "parent": "block/cube",
                "textures": {
                    "particle": "minecraft:block/gold_block",
                    "bar": "minecraft:block/gold_block",
                    "plate": "minecraft:block/lapis_block"
                },
                "elements": [
                    {
                        "from": [0, 0, 0],
                        "to": [16, 1, 16],
                        "faces": {
                            "up": { "uv": [0, 0, 16, 16], "texture": "#plate" },
                            "down": { "uv": [0, 0, 16, 16], "texture": "#plate", "cullface": "down" }
                        }
                    },
                    {
                        "from": [2, 1, 0],
                        "to": [4, 2, 16],
                        "rotation": { "angle": 22.5, "axis": "y", "origin": [8, 8, 8] },
                        "faces": {
                            "up": { "uv": [0, 0, 2, 16], "rotation": 90, "texture": "#bar", "tintindex": 0 },
                            "north": { "texture": "#bar", "uv": [2, 14, 4, 15] }
                        }
                    }
                ]
            }
            """.trimIndent() + "\n",
        )
        return model.readText()
    }

    @Test
    fun aReimportOfAnUnchangedModelLeavesItsFileByteForByte() {
        val before = laidOut()

        imported(exported())

        assertEquals(before, model.readText())
    }

    @Test
    fun aReimportChangesOnlyTheLineOfWhatWasEdited() {
        val before = laidOut().lines()
        @Suppress("UNCHECKED_CAST")
        val project = JsonSlurper().parse(exported()) as MutableMap<String, Any?>
        val north = (((project["elements"] as List<*>)[1] as Map<*, *>)["faces"] as Map<*, *>)["north"]
        @Suppress("UNCHECKED_CAST")
        (north as MutableMap<String, Any?>)["uv"] = listOf(3, 14, 5, 15)

        imported(directory.resolve("export/station_rail.bbmodel").apply { writeText(JsonOutput.toJson(project)) })

        val after = model.readText().lines()
        assertEquals(before.size, after.size)
        assertEquals(
            listOf("""                "north": { "texture": "#bar", "uv": [3, 14, 5, 15] }"""),
            after.filterIndexed { i, line -> line != before[i] },
        )
    }

    @Test
    fun anExportEmbedsEachTextureOnceWithTheParticleFlag() {
        station()

        val project = JsonSlurper().parse(exported()) as Map<*, *>

        val textures = (project["textures"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("bar" to true, "plate" to false), textures.map { it["id"] to it["particle"] })
        assertEquals(listOf("block" to "minecraft"), textures.map { it["folder"] to it["namespace"] }.distinct())
        assertTrue((textures[0]["source"] as String).startsWith("data:image/png;base64,"))
        assertEquals("java_block", (project["meta"] as Map<*, *>)["model_format"])
    }

    /** The project [file] with the texture [key] painted [rgb] in Blockbench */
    private fun painted(
        file: File,
        key: String,
        rgb: Int,
    ): File {
        @Suppress("UNCHECKED_CAST")
        val project = JsonSlurper().parse(file) as MutableMap<String, Any?>
        (project["textures"] as List<*>)
            .map {
                @Suppress("UNCHECKED_CAST")
                it as MutableMap<String, Any?>
            }.single { it["id"] == key }["source"] = "data:image/png;base64," + Base64.getEncoder().encodeToString(png(16, 16, rgb))
        return file.apply { writeText(JsonOutput.toJson(project)) }
    }

    @Test
    fun aReimportOverwritesTheModelsOwnTexturesAndDropsTheirStaleArt() {
        val bar = assets.resolve("nordtal/textures/block/station_rail_bar.png").apply { parentFile.mkdirs() }
        bar.writeBytes(png(16, 16, 0xFFFF0000.toInt()))
        station(""""particle": "#bar", "bar": "nordtal:block/station_rail_bar", "plate": "minecraft:block/lapis_block"""")
        val art = pack.resolve("art/assets/nordtal/textures/block/station_rail_bar.aseprite").apply { parentFile.mkdirs() }
        art.writeText("aseprite")

        val written = imported(painted(exported(), "bar", 0xFF00FF00.toInt()))

        assertTrue(bar in written && art in written)
        assertEquals(0xFF00FF00.toInt(), ImageIO.read(bar).getRGB(5, 5))
        assertTrue(!art.exists())
        assertEquals("nordtal:block/station_rail_bar", textures()["bar"])
        assertEquals("minecraft:block/lapis_block", textures()["plate"])
    }

    @Test
    fun aVanillaTexturePaintedInBlockbenchBecomesATextureOfThePack() {
        station()

        imported(painted(exported(), "plate", 0xFF00FF00.toInt()))

        val plate = textures()["plate"] as String
        assertTrue(plate.startsWith("nordtal:"), plate)
        assertEquals("minecraft:block/gold_block", textures()["bar"])
    }

    /** The project [file] with each embedded PNG decoded and encoded again, as an editor that saves it does */
    private fun resaved(file: File): File {
        @Suppress("UNCHECKED_CAST")
        val project = JsonSlurper().parse(file) as MutableMap<String, Any?>
        (project["textures"] as List<*>).forEach {
            @Suppress("UNCHECKED_CAST")
            val texture = it as MutableMap<String, Any?>
            val bytes = Base64.getDecoder().decode((texture["source"] as String).substringAfter(","))
            val image = ImageIO.read(bytes.inputStream())
            val again =
                BufferedImage(
                    image.width,
                    image.height,
                    BufferedImage.TYPE_INT_ARGB,
                ).apply { createGraphics().drawImage(image, 0, 0, null) }
            val png = ByteArrayOutputStream().also { out -> ImageIO.write(again, "png", out) }.toByteArray()
            texture["source"] = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        }
        return file.apply { writeText(JsonOutput.toJson(project)) }
    }

    @Test
    fun aTextureThatBlockbenchSavedAgainWithTheSamePixelsStaysTheTextureItCameFrom() {
        val bar = assets.resolve("nordtal/textures/block/station_rail_bar.png").apply { parentFile.mkdirs() }
        bar.writeBytes(png(16, 16, 0xFFFF0000.toInt()))
        val before = OxfmtJson.read(station(""""bar": "nordtal:block/station_rail_bar", "plate": "minecraft:block/lapis_block""""))
        val art = pack.resolve("art/assets/nordtal/textures/block/station_rail_bar.aseprite").apply { parentFile.mkdirs() }
        art.writeText("aseprite")

        imported(resaved(exported()))

        assertEquals(before, OxfmtJson.read(model))
        assertTrue(art.exists())
        assertEquals(listOf("block"), assets.resolve("nordtal/textures").list()!!.toList())
    }

    @Test
    fun aTextureThatAnotherModelNamesTooIsCopiedByAReimportInsteadOfOverwritten() {
        val bar = assets.resolve("nordtal/textures/block/station_rail_bar.png").apply { parentFile.mkdirs() }
        bar.writeBytes(png(16, 16, 0xFFFF0000.toInt()))
        station(""""bar": "nordtal:block/station_rail_bar", "plate": "minecraft:block/lapis_block"""")
        assets.resolve("nordtal/models/item/other.json").writeText("""{ "textures": { "all": "nordtal:block/station_rail_bar" } }""")

        imported(painted(exported(), "bar", 0xFF00FF00.toInt()))

        assertEquals(0xFFFF0000.toInt(), ImageIO.read(bar).getRGB(5, 5))
        assertEquals("nordtal:item/station_rail_bar", textures()["bar"])
    }

    @Test
    fun aReimportDeletesATextureOfItsOwnThatTheModelNoLongerNames() {
        val bar = assets.resolve("nordtal/textures/block/station_rail_bar.png").apply { parentFile.mkdirs() }
        bar.writeBytes(png(16, 16, 0xFFFF0000.toInt()))
        station(""""particle": "#bar", "bar": "nordtal:block/station_rail_bar", "plate": "minecraft:block/lapis_block"""")
        val art = pack.resolve("art/assets/nordtal/textures/block/station_rail_bar.aseprite").apply { parentFile.mkdirs() }
        art.writeText("aseprite")
        @Suppress("UNCHECKED_CAST")
        val project = JsonSlurper().parse(exported()) as MutableMap<String, Any?>
        // In Blockbench every face and the particle now draw the plate, and the bar texture is gone
        val textures = (project["textures"] as List<*>).map { it as MutableMap<*, *> }
        val plate = textures.indexOfFirst { it["id"] == "plate" }
        (project["elements"] as List<*>).forEach { element ->
            ((element as Map<*, *>)["faces"] as Map<*, *>).values.forEach {
                @Suppress("UNCHECKED_CAST")
                (it as MutableMap<String, Any?>)["texture"] = 0
            }
        }
        project["textures"] =
            listOf(
                textures[plate].also {
                    @Suppress("UNCHECKED_CAST")
                    (it as MutableMap<String, Any?>)["particle"] = true
                },
            )
        val file = directory.resolve("export/station_rail.bbmodel").apply { writeText(JsonOutput.toJson(project)) }

        val written = imported(file)

        assertTrue(bar in written && art in written)
        assertTrue(!bar.exists() && !art.exists())
        assertTrue("nordtal:block/station_rail_bar" !in textures().values)
    }

    @Test
    fun anExportRefusesTexturesOfOneFileNameAndAFaceThatDrawsNoTexture() {
        assets.resolve("nordtal/textures/block/lapis_block.png").apply { parentFile.mkdirs() }.writeBytes(png(16, 16))
        station(""""bar": "nordtal:block/lapis_block", "plate": "minecraft:block/lapis_block"""")

        assertEquals("model", assertThrows<FieldRefusal> { exported() }.field)

        station(""""plate": "minecraft:block/lapis_block"""")

        assertTrue("#bar" in assertThrows<FieldRefusal> { exported() }.message!!)
    }

    private companion object {
        const val STATION_TEXTURES =
            """"particle": "minecraft:block/gold_block", "bar": "minecraft:block/gold_block", "plate": "minecraft:block/lapis_block""""
    }
}
