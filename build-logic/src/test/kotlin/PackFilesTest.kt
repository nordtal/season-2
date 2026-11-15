package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackFilesTest {
    @TempDir
    lateinit var pack: File

    private val assets get() = pack.resolve("src/assets")

    private fun file(
        path: String,
        text: String,
    ): File = assets.resolve(path).also { it.parentFile.mkdirs() }.apply { writeText(text) }

    private fun jar(vararg entries: Pair<String, String>): File {
        val jar = pack.resolve("client.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun filesShowsEveryFileThatNoOtherPartOfTheTreeShows() {
        file("minecraft/blockstates/stone.json", "{}")
        file("minecraft/texts/splashes.txt", "Hi")
        file("minecraft/shaders/core/rendertype_text.fsh", "void main() {}")
        file("nordtal/models/item/lamp.json", "{}")
        file("minecraft/textures/block/stone.png", "")
        file("minecraft/lang/en_us.json", "{}")
        file("nordtal/items/lamp.json", "{}")
        file("nordtal/sounds.json", "{}")
        file("nordtal/sounds/beep.ogg", "")
        file("minecraft/font/default.json", "{}")

        assertEquals(
            listOf(
                "minecraft/blockstates/stone.json",
                "minecraft/shaders/core/rendertype_text.fsh",
                "minecraft/texts/splashes.txt",
                "nordtal/models/item/lamp.json",
            ),
            PackFiles.list(assets).map { it.relativeTo(assets).invariantSeparatorsPath },
        )
    }

    @Test
    fun aFileTakesTheKindOfItsFirstFolder() {
        assertEquals("model", PackFiles.kind("nordtal/models/item/lamp.json"))
        assertEquals("blockstate", PackFiles.kind("minecraft/blockstates/stone.json"))
        assertEquals("text", PackFiles.kind("minecraft/texts/splashes.txt"))
        assertEquals("file", PackFiles.kind("minecraft/regional_compliancies.json"))
        assertEquals("file", PackFiles.kind("minecraft/waypoint_style/default.json"))
    }

    @Test
    fun aModelNamesEachParentAndTextureOutsideMinecraftThatThePackLacks() {
        file("nordtal/textures/item/lamp.png", "")
        val model =
            file(
                "nordtal/models/item/lamp.json",
                """{ "parent": "nordtal:item/base", "textures": { "layer0": "nordtal:item/lamp", "layer1": "nordtal:item/gone",
                   "side": "#layer0", "top": "minecraft:block/stone" } }""",
            )

        assertEquals(
            listOf(
                "Its parent names nordtal:item/base, which the pack does not hold.",
                "The texture layer1 names nordtal:item/gone, which the pack does not hold.",
            ),
            PackFiles.problems(assets, model),
        )
    }

    @Test
    fun aModelElementNeedsItsCornersAndFaces() {
        val model = file("nordtal/models/block/box.json", """{ "elements": [ { "from": [0, 0, 0], "to": [16, 16], "faces": {} } ] }""")

        assertEquals(listOf("Element 1 has no to of three numbers.", "Element 1 has no faces."), PackFiles.problems(assets, model))
    }

    @Test
    fun aBlockStateNeedsVariantsOrMultipartWithModelsThePackOrTheJarHolds() {
        val both = file("minecraft/blockstates/stone.json", """{ "variants": {}, "multipart": [] }""")
        val variants =
            file(
                "minecraft/blockstates/lamp.json",
                """{ "variants": { "lit=true": { "model": "nordtal:block/lamp_on" }, "lit=false": [ { "model": "block/stone" } ] } }""",
            )
        val multipart = file("minecraft/blockstates/fence.json", """{ "multipart": [ { "when": {} } ] }""")

        assertEquals(listOf("It needs either variants or multipart, and not both."), PackFiles.problems(assets, both))
        assertEquals(
            listOf("The variant \"lit=true\" names nordtal:block/lamp_on, which the pack does not hold."),
            PackFiles.problems(assets, variants),
        )
        assertEquals(listOf("Part 1 of multipart has no apply."), PackFiles.problems(assets, multipart))
    }

    @Test
    fun aFileThatIsNoJsonSaysSoAndATextFileIsNotJudged() {
        val broken = file("minecraft/atlases/blocks.json", """{ "sources": [ """)
        val text = file("minecraft/texts/splashes.txt", "{ not json")

        assertTrue(PackFiles.problems(assets, broken).single().startsWith("It is no JSON:"))
        assertEquals(emptyList<String>(), PackFiles.problems(assets, text))
    }

    @Test
    fun equipmentAndParticlesNameTheirTexturesInTheirOwnFolders() {
        file("nordtal/textures/entity/equipment/humanoid/steel.png", "")
        val equipment =
            file(
                "nordtal/equipment/steel.json",
                """{ "layers": { "humanoid": [ { "texture": "nordtal:steel" } ], "wings": [ { "texture": "nordtal:steel" } ] } }""",
            )
        val particle = file("minecraft/particles/glow.json", """{ "textures": [ "nordtal:glow", "minecraft:glow" ] }""")

        assertEquals(listOf("Layer 1 of wings names nordtal:steel, which the pack does not hold."), PackFiles.problems(assets, equipment))
        assertEquals(listOf("The texture names nordtal:glow, which the pack does not hold."), PackFiles.problems(assets, particle))
    }

    @Test
    fun checkPackListsTheProblemsOfEveryFileByItsPath() {
        file("minecraft/blockstates/stone.json", "{}")

        assertEquals(
            listOf("minecraft/blockstates/stone.json: It needs either variants or multipart, and not both."),
            PackFiles.findings(assets),
        )
    }

    @Test
    fun aNewFileStartsFromTheTemplateOfItsKindAndPassesTheCheck() {
        val model = PackFiles.create(pack, "nordtal:models/item/lamp.json").single()
        val state = PackFiles.create(pack, "minecraft:blockstates/stone.json").single()
        val text = PackFiles.create(pack, "minecraft:texts/end.txt").single()

        assertTrue(model.readText().contains("\"parent\": \"minecraft:item/generated\""))
        assertEquals(emptyList<String>(), PackFiles.problems(assets, model) + PackFiles.problems(assets, state))
        assertEquals("", text.readText())
        assertEquals(
            "nordtal:models/item/lamp.json exists already.",
            assertThrows<FieldRefusal> { PackFiles.create(pack, "nordtal:models/item/lamp.json") }.message,
        )
    }

    @Test
    fun aNewFileIsRefusedWhereAnotherPartOfTheTreeOrTheBuildWritesIt() {
        val font = assertThrows<FieldRefusal> { PackFiles.create(pack, "minecraft:font/default.json") }
        val texture = assertThrows<FieldRefusal> { PackFiles.create(pack, "nordtal:textures/item/lamp.png") }
        val folder = assertThrows<FieldRefusal> { PackFiles.create(pack, "nordtal:models/item") }
        val id = assertThrows<FieldRefusal> { PackFiles.create(pack, "Nordtal:models/item/lamp.json") }

        assertEquals("asset", font.field)
        assertTrue(font.message!!.contains("create a font in Glyphs"))
        assertTrue(texture.message!!.contains("as a texture"))
        assertTrue(folder.message!!.contains("file name with an extension"))
        assertTrue(id.message!!.contains("namespace"))
        assertFalse(assets.exists())
    }

    @Test
    fun aVanillaFileIsCopiedFromTheJarOnce() {
        val jar = jar("assets/minecraft/blockstates/stone.json" to """{ "variants": {} }""")

        val copied = PackFiles.copy(pack, jar, "minecraft:blockstates/stone.json").single()

        assertEquals("""{ "variants": {} }""", copied.readText())
        assertThrows<FieldRefusal> { PackFiles.copy(pack, jar, "minecraft:blockstates/stone.json") }
        assertEquals(
            "The client jar has no minecraft:blockstates/glass.json.",
            assertThrows<FieldRefusal> { PackFiles.copy(pack, jar, "minecraft:blockstates/glass.json") }.message,
        )
    }

    @Test
    fun theJarListsTheFilesThatFilesShows() {
        assertEquals(
            listOf("minecraft:blockstates/stone.json", "minecraft:texts/splashes.txt"),
            PackFiles.vanilla(
                listOf(
                    "assets/minecraft/texts/splashes.txt",
                    "assets/minecraft/blockstates/stone.json",
                    "assets/minecraft/textures/block/stone.png",
                    "assets/minecraft/font/default.json",
                    "assets/minecraft/models/",
                    "data/minecraft/recipe/stone.json",
                ),
            ),
        )
    }

    @Test
    fun aModelOfThePackIsNotDeletedWhileAnotherFileNamesIt() {
        val model = file("nordtal/models/block/lamp.json", "{}")
        file("minecraft/blockstates/redstone_lamp.json", """{ "variants": {
            "": { "model": "nordtal:block/lamp" } } }""")
        file("nordtal/models/block/lamp_on.json", """{ "textures": { "all": "nordtal:block/lamp" } }""")

        val refusal = assertThrows<IllegalStateException> { PackFiles.delete(pack, null, "nordtal:models/block/lamp.json") }

        assertEquals("The model nordtal:block/lamp is still used in 1 place:\n    src/assets/minecraft/blockstates/redstone_lamp.json:2", refusal.message)
        assertTrue(model.isFile)
        assertEquals(listOf(assets.resolve("minecraft/blockstates/redstone_lamp.json")), PackFiles.delete(pack, null, "minecraft:blockstates/redstone_lamp.json"))
        PackFiles.delete(pack, null, "nordtal:models/block/lamp.json")
        assertFalse(model.exists())
    }
}
