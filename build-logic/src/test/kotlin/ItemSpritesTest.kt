package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ItemSpritesTest {
    @TempDir
    lateinit var directory: File

    private val png = ByteArray(8)

    /** A client jar shaped like the one of 26.3: items in `minecraft:items`, blocks in `minecraft:blocks`. */
    private val files =
        mapOf(
            "assets/minecraft/atlases/items.json" to """{"sources": [{"type": "minecraft:directory", "prefix": "item/", "source": "item"}]}""",
            "assets/minecraft/atlases/blocks.json" to """{"sources": [{"type": "minecraft:directory", "prefix": "block/", "source": "block"}]}""",
            "assets/minecraft/models/item/generated.json" to """{"parent": "builtin/generated"}""",
            "assets/minecraft/models/block/cube_all.json" to """{"textures": {"particle": "#all"}}""",
            "assets/minecraft/models/block/cube_column.json" to """{"textures": {"particle": "#side"}}""",
            "assets/minecraft/items/diamond.json" to model("minecraft:item/diamond"),
            "assets/minecraft/models/item/diamond.json" to """{"parent": "item/generated", "textures": {"layer0": "minecraft:item/diamond"}}""",
            "assets/minecraft/items/stone.json" to model("minecraft:block/stone"),
            "assets/minecraft/models/block/stone.json" to """{"parent": "block/cube_all", "textures": {"all": "block/stone"}}""",
            "assets/minecraft/items/oak_log.json" to model("minecraft:block/oak_log"),
            "assets/minecraft/models/block/oak_log.json" to
                """{"parent": "block/cube_column", "textures": {"end": "block/oak_log_top", "side": "block/oak_log"}}""",
            "assets/minecraft/items/oak_leaves.json" to
                """{"model": {"type": "minecraft:model", "model": "minecraft:block/stone", "tints": [{"type": "minecraft:constant", "value": -1}]}}""",
            "assets/minecraft/items/chest.json" to """{"model": {"type": "minecraft:special", "base": "minecraft:item/chest", "model": {}}}""",
            "assets/minecraft/items/barrier.json" to model("minecraft:item/barrier"),
            "assets/minecraft/models/item/barrier.json" to """{"textures": {"layer0": "minecraft:item/barrier"}}""",
            "assets/minecraft/items/painting.json" to model("minecraft:item/painting"),
            "assets/minecraft/models/item/painting.json" to """{"textures": {"layer0": "minecraft:painting/back"}}""",
            "assets/minecraft/items/compass.json" to model("minecraft:item/missing"),
        )

    private val textures =
        listOf(
            "assets/minecraft/textures/item/diamond.png",
            "assets/minecraft/textures/block/stone.png",
            "assets/minecraft/textures/block/oak_log.png",
            "assets/minecraft/textures/block/oak_log_top.png",
            "assets/minecraft/textures/painting/back.png",
        )

    private fun model(model: String) = """{"model": {"type": "minecraft:model", "model": "$model"}}"""

    private fun jar(): File =
        directory.resolve("client.jar").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                files.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                textures.forEach { path ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(png)
                    zip.closeEntry()
                }
            }
        }

    private val assets get() = directory.resolve("pack/src/assets").apply { mkdirs() }

    @Test
    fun aFlatItemDrawsItsLayerAndABlockItsParticleEachInTheAtlasThatHoldsIt() {
        assertEquals(
            mapOf(
                "minecraft:diamond" to ItemSprites.Sprite("minecraft:items", "minecraft:item/diamond"),
                "minecraft:oak_log" to ItemSprites.Sprite("minecraft:blocks", "minecraft:block/oak_log"),
                "minecraft:stone" to ItemSprites.Sprite("minecraft:blocks", "minecraft:block/stone"),
            ),
            ItemSprites.of(assets, jar()),
        )
    }

    /** A tinted model, a special model, a missing texture, a texture outside every atlas and a missing model draw none. */
    @Test
    fun anItemWhoseSpriteWouldDrawWronglyHasNone() {
        val sprites = ItemSprites.of(assets, jar())

        listOf("oak_leaves", "chest", "barrier", "painting", "compass").forEach {
            assertEquals(null, sprites["minecraft:$it"], it)
        }
    }

    @Test
    fun anItemOfThePackDrawsATextureOfThePackFromAnAtlasThePackAddsTo() {
        assets.resolve("nordtal/items/relic.json").apply { parentFile.mkdirs() }.writeText(model("nordtal:item/relic"))
        assets.resolve("nordtal/models/item/relic.json").apply { parentFile.mkdirs() }.writeText("""{"textures": {"layer0": "nordtal:relic"}}""")
        assets.resolve("nordtal/textures/relic.png").apply { parentFile.mkdirs() }.writeBytes(png)
        assets
            .resolve("minecraft/atlases/items.json")
            .apply { parentFile.mkdirs() }
            .writeText("""{"sources": [{"type": "single", "resource": "nordtal:relic", "sprite": "nordtal:item/relic"}]}""")

        val sprites = ItemSprites.of(assets, jar())

        assertEquals(ItemSprites.Sprite("minecraft:items", "nordtal:item/relic"), sprites["nordtal:relic"])
        assertEquals(ItemSprites.Sprite("minecraft:items", "minecraft:item/diamond"), sprites["minecraft:diamond"])
    }

    @Test
    fun theTableIsSortedWithTheColonsOfEachItemEscaped() {
        assertEquals(
            "minecraft\\:diamond=minecraft:items minecraft:item/diamond\nminecraft\\:stone=minecraft:blocks minecraft:block/stone\n",
            ItemSprites.properties(
                sortedMapOf(
                    "minecraft:stone" to ItemSprites.Sprite("minecraft:blocks", "minecraft:block/stone"),
                    "minecraft:diamond" to ItemSprites.Sprite("minecraft:items", "minecraft:item/diamond"),
                ),
            ),
        )
    }
}
