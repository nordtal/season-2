package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ItemDeletesTest {
    @TempDir
    lateinit var repository: File

    private val pack get() = repository.resolve("resource-pack")

    private fun jar(): File =
        repository.resolve("client.jar").also { file ->
            if (file.isFile) return@also
            ZipOutputStream(file.outputStream()).use { zip ->
                mapOf(
                    "assets/minecraft/models/item/generated.json" to """{ "parent": "builtin/generated" }""",
                    "assets/minecraft/textures/block/stone.png" to "png",
                ).forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun files() = ItemFiles(pack, ::jar)

    private fun write(
        path: String,
        text: String = "png",
    ) = pack.resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    private fun item(
        id: String,
        model: String,
    ) = write("src/assets/nordtal/items/$id.json", """{ "model": { "type": "minecraft:model", "model": "$model" } }""")

    private fun model(
        path: String,
        parent: String,
        vararg textures: String,
    ) = write(
        "src/assets/nordtal/models/$path.json",
        """{ "parent": "$parent", "textures": { ${textures.withIndex().joinToString { (i, t) -> "\"layer$i\": \"$t\"" }} } }""",
    )

    private fun exists(path: String) = pack.resolve(path).exists()

    @BeforeEach
    fun repository() {
        val process = ProcessBuilder("git", "init", "-q").directory(repository).inheritIO().start()
        assertEquals(0, process.waitFor())
    }

    @Test
    fun aTextureGoesWithItsAnimationAndSource() {
        write("src/assets/nordtal/textures/item/wand.png")
        write("src/assets/nordtal/textures/item/wand.png.mcmeta", "{}")
        write("art/assets/nordtal/textures/item/wand.aseprite")

        val deleted = files().use { it.deleteTexture("nordtal:item/wand.png", repository) }

        assertEquals(3, deleted.size)
        assertFalse(exists("src/assets/nordtal/textures/item/wand.png"))
        assertFalse(exists("src/assets/nordtal/textures/item/wand.png.mcmeta"))
        assertFalse(exists("art/assets/nordtal/textures/item/wand.aseprite"))
    }

    @Test
    fun aTextureThatAModelOrAGlyphDrawsStaysAndEachPlaceIsListed() {
        write("src/assets/nordtal/textures/item/wand.png")
        model("item/wand", "minecraft:item/generated", "nordtal:item/wand")
        write("glyphs.json", """{ "fonts": [{ "blocks": [{ "glyphs": [{ "texture": "nordtal:item/wand.png" }] }] }] }""")

        val refused = assertThrows<IllegalStateException> { files().use { it.deleteTexture("nordtal:item/wand", repository) } }

        assertEquals(
            "nordtal:item/wand is still used in 2 places:\n" +
                "    resource-pack/glyphs.json:1\n" +
                "    resource-pack/src/assets/nordtal/models/item/wand.json:1",
            refused.message!!
                .lines()
                .let { listOf(it[0]) + it.drop(1).sorted() }
                .joinToString("\n"),
        )
        assertTrue(exists("src/assets/nordtal/textures/item/wand.png"))
    }

    @Test
    fun anOverrideOfTheClientJarGoesEvenWhileAModelDrawsIt() {
        write("src/assets/minecraft/textures/block/stone.png")
        model("block/rock", "minecraft:block/cube_all", "minecraft:block/stone")

        files().use { it.deleteTexture("minecraft:block/stone.png", repository) }

        assertFalse(exists("src/assets/minecraft/textures/block/stone.png"))
    }

    @Test
    fun aTextureThatThePackLacksIsRefused() {
        val refused = assertThrows<FieldRefusal> { files().use { it.deleteTexture("nordtal:item/none", repository) } }

        assertEquals("texture", refused.field)
    }

    @Test
    fun anItemGoesWithEachModelAndTextureThatNothingElseDraws() {
        item("wand", "nordtal:item/wand")
        model("item/wand", "nordtal:item/handheld", "nordtal:item/wand", "nordtal:item/shared", "minecraft:block/stone")
        model("item/handheld", "minecraft:item/generated", "nordtal:item/grip")
        write("src/assets/nordtal/textures/item/wand.png")
        write("art/assets/nordtal/textures/item/wand.aseprite")
        write("src/assets/nordtal/textures/item/grip.png")
        write("src/assets/nordtal/textures/item/shared.png")
        write("src/assets/minecraft/textures/block/stone.png")
        item("rod", "nordtal:item/rod")
        model("item/rod", "minecraft:item/generated", "nordtal:item/shared")

        files().use { it.deleteItem("nordtal:wand", repository) }

        assertFalse(exists("src/assets/nordtal/items/wand.json"))
        assertFalse(exists("src/assets/nordtal/models/item/wand.json"))
        assertFalse(exists("src/assets/nordtal/models/item/handheld.json"))
        assertFalse(exists("src/assets/nordtal/textures/item/wand.png"))
        assertFalse(exists("art/assets/nordtal/textures/item/wand.aseprite"))
        assertFalse(exists("src/assets/nordtal/textures/item/grip.png"))
        assertTrue(exists("src/assets/nordtal/textures/item/shared.png"))
        assertTrue(exists("src/assets/minecraft/textures/block/stone.png"))
        assertTrue(exists("src/assets/nordtal/items/rod.json"))
    }

    @Test
    fun aModelThatAnotherItemDrawsStaysWithItsTextures() {
        item("wand", "nordtal:item/wand")
        item("staff", "nordtal:item/wand")
        model("item/wand", "minecraft:item/generated", "nordtal:item/wand")
        write("src/assets/nordtal/textures/item/wand.png")

        files().use { it.deleteItem("nordtal:wand", repository) }

        assertFalse(exists("src/assets/nordtal/items/wand.json"))
        assertTrue(exists("src/assets/nordtal/models/item/wand.json"))
        assertTrue(exists("src/assets/nordtal/textures/item/wand.png"))
    }

    @Test
    fun anItemThatCodeNamesStaysAndEachPlaceIsListed() {
        item("wand", "nordtal:item/wand")
        model("item/wand", "minecraft:item/generated", "nordtal:item/wand")
        repository.resolve("plugin/Wand.java").apply { parentFile.mkdirs() }.writeText(
            "class Wand {\n    String id = \"nordtal:wand\";\n    String other = \"nordtal:wand_old\";\n}\n",
        )

        val refused = assertThrows<IllegalStateException> { files().use { it.deleteItem("nordtal:wand", repository) } }

        assertEquals("The item nordtal:wand is still used in 1 place:\n    plugin/Wand.java:2", refused.message)
        assertTrue(exists("src/assets/nordtal/items/wand.json"))
    }

    @Test
    fun anItemThatThePackLacksIsRefused() {
        val refused = assertThrows<FieldRefusal> { files().use { it.deleteItem("nordtal:none", repository) } }

        assertEquals("item", refused.field)
    }

    @Test
    fun anItemThatCodeGivesAsAKeyStaysAndADocumentOrTestThatNamesItDoesNot() {
        item("wand", "nordtal:item/wand")
        model("item/wand", "minecraft:item/generated", "nordtal:item/wand")
        repository.resolve("plugin/src/main/java/Wand.java").apply { parentFile.mkdirs() }.writeText(
            "class Wand {\n    static final Key MODEL = Key.key(\"nordtal\", \"wand\");\n}\n",
        )
        repository.resolve("plugin/src/test/java/WandTest.java").apply { parentFile.mkdirs() }.writeText("\"nordtal:wand\"\n")
        repository.resolve("README.md").writeText("Such as `nordtal:wand`.\n")
        repository.resolve("designer/spec/tab_spec.lua").apply { parentFile.mkdirs() }.writeText("local id = \"nordtal:wand\"\n")

        val refused = assertThrows<IllegalStateException> { files().use { it.deleteItem("nordtal:wand", repository) } }

        assertEquals("The item nordtal:wand is still used in 1 place:\n    plugin/src/main/java/Wand.java:2", refused.message)
    }
}
