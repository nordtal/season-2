package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ItemDefinitionsTest {
    @TempDir
    lateinit var directory: File

    private val pack get() = directory.resolve("pack")

    /** A client jar with the vanilla files that the examples reference. */
    private fun jar(): File =
        directory.resolve("client.jar").also { file ->
            if (file.isFile) return@also
            ZipOutputStream(file.outputStream()).use { zip ->
                mapOf(
                    "assets/minecraft/models/item/generated.json" to """{ "parent": "builtin/generated" }""",
                    "assets/minecraft/models/item/apple.json" to
                        """{ "parent": "minecraft:item/generated", "textures": { "layer0": "minecraft:item/apple" } }""",
                    "assets/minecraft/textures/item/apple.png" to "png",
                    "assets/minecraft/textures/block/stone.png" to "png",
                ).forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun files() = ItemFiles(pack, ::jar)

    private fun model(json: String) = """{ "model": $json }"""

    private val apple = """{ "type": "minecraft:model", "model": "minecraft:item/apple" }"""

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{ "type": "minecraft:empty" }""",
            """{ "type": "model", "model": "item/apple", "tints": [{ "type": "minecraft:constant", "value": -1 }, { "type": "minecraft:dye", "default": 0 }, { "type": "minecraft:grass", "temperature": 0.5, "downfall": 1 }, { "type": "minecraft:custom_model_data", "default": 0 }, { "type": "minecraft:firework", "default": 0 }, { "type": "minecraft:potion", "default": 0 }, { "type": "minecraft:map_color", "default": 0 }, { "type": "minecraft:team", "default": 0 }] }""",
            """{ "type": "minecraft:composite", "models": [APPLE, { "type": "minecraft:empty" }] }""",
            """{ "type": "minecraft:condition", "property": "minecraft:using_item", "on_true": APPLE, "on_false": APPLE }""",
            """{ "type": "minecraft:condition", "property": "minecraft:has_component", "component": "minecraft:dyed_color", "on_true": APPLE, "on_false": APPLE }""",
            """{ "type": "minecraft:condition", "property": "minecraft:component", "predicate": "minecraft:damage", "value": {}, "on_true": APPLE, "on_false": APPLE }""",
            """{ "type": "minecraft:condition", "property": "minecraft:keybind_down", "keybind": "key.use", "on_true": APPLE, "on_false": APPLE }""",
            """{ "type": "minecraft:select", "property": "minecraft:display_context", "cases": [{ "when": ["gui", "ground"], "model": APPLE }], "fallback": APPLE }""",
            """{ "type": "minecraft:select", "property": "minecraft:local_time", "pattern": "MM-dd", "locale": "", "cases": [{ "when": "12-24", "model": APPLE }] }""",
            """{ "type": "minecraft:select", "property": "minecraft:block_state", "block_state_property": "age", "cases": [] }""",
            """{ "type": "minecraft:select", "property": "minecraft:component", "component": "minecraft:custom_name", "cases": [] }""",
            """{ "type": "minecraft:range_dispatch", "property": "minecraft:time", "source": "daytime", "scale": 1, "entries": [{ "threshold": 0.5, "model": APPLE }], "fallback": APPLE }""",
            """{ "type": "minecraft:range_dispatch", "property": "minecraft:compass", "target": "spawn", "entries": [] }""",
            """{ "type": "minecraft:range_dispatch", "property": "minecraft:count", "normalize": true, "entries": [] }""",
            """{ "type": "minecraft:special", "base": "minecraft:item/apple", "model": { "type": "minecraft:chest", "texture": "normal" } }""",
            """{ "type": "minecraft:special", "base": "minecraft:item/apple", "model": { "type": "minecraft:head", "kind": "skeleton" } }""",
            """{ "type": "minecraft:bundle/selected_item" }""",
        ],
    )
    fun everyModelTypeIsReadCheckedAndWrittenBack(json: String) {
        val text = model(json.replace("APPLE", apple))

        val written = files().use { it.write("nordtal:example", text) }

        assertEquals(OxfmtJson.read(text), OxfmtJson.read(written))
        assertEquals(emptyList<String>(), files().use { it.describe("nordtal:example")["problems"] })
    }

    @Test
    fun anUnknownTypeAMissingFieldAndAnUnknownFieldAreEachNamedWithTheirPlace() {
        val definition =
            OxfmtJson.read(
                model(
                    """{ "type": "minecraft:select", "property": "minecraft:block_state", "cases": [{ "when": "1", "model": { "type": "minecraft:cube" } }],
                       "fallback": { "type": "minecraft:model", "model": "item/apple", "tint": [] } }""",
                ),
            )

        assertEquals(
            listOf(
                "model > property needs 'block_state_property'",
                "model > cases[0] > model has no known 'type': minecraft:cube",
                "model > fallback has no field 'tint'",
            ),
            ItemDefinitions.problems(definition),
        )
    }

    @Test
    fun aCommentKeyIsAllowed() {
        assertEquals(
            emptyList<String>(),
            ItemDefinitions.problems(OxfmtJson.read("""{ "_comment": "x", "model": { "type": "minecraft:empty" } }""")),
        )
    }

    @Test
    fun aDefinitionThatReferencesAMissingModelOrTextureIsRefusedAndNotWritten() {
        pack.resolve("src/assets/nordtal/models/item/wand.json").apply { parentFile.mkdirs() }.writeText(
            """{ "parent": "minecraft:item/generated", "textures": { "layer0": "nordtal:item/wand", "layer1": { "sprite": "item/apple" } } }""",
        )

        val error =
            assertThrows<IllegalStateException> {
                files().use {
                    it.write(
                        "nordtal:wand",
                        model(
                            """{ "type": "minecraft:composite", "models": [{ "type": "model", "model": "nordtal:item/wand" }, { "type": "model", "model": "nordtal:item/gone" }] }""",
                        ),
                    )
                }
            }

        assertEquals(
            "The item nordtal:wand breaks 2 rule(s):\n" +
                "    texture nordtal:item/wand of model nordtal:item/wand is in neither the pack nor the client jar\n" +
                "    model nordtal:item/gone is in neither the pack nor the client jar",
            error.message,
        )
        assertTrue(!pack.resolve("src/assets/nordtal/items/wand.json").exists())
    }

    @Test
    fun aVanillaItemIsDescribedFromTheJarAndAnOverrideFromThePack() {
        ZipOutputStream(directory.resolve("client.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/minecraft/items/apple.json"))
            zip.write(model(apple).toByteArray())
            zip.putNextEntry(ZipEntry("assets/minecraft/models/item/apple.json"))
            zip.write("{}".toByteArray())
        }

        val vanilla = files().use { it.describe("minecraft:apple") }
        val written = files().use { it.write("minecraft:apple", model("""{ "type": "minecraft:empty" }""")) }
        val override = files().use { it.describe("apple") }

        assertEquals(
            listOf("vanilla", null, OxfmtJson.read(model(apple))),
            listOf(vanilla["source"], vanilla["file"], vanilla["definition"]),
        )
        assertEquals(listOf("pack", written.absolutePath), listOf(override["source"], override["file"]))
    }

    @Test
    fun aBlockbenchModelIsImportedWithItsTexturesUnderTheChosenNamespace() {
        val export = directory.resolve("export").apply { mkdirs() }
        export.resolve("lamp.json").writeText(
            """{ "credit": "Made with Blockbench", "texture_size": [32, 32],
                 "textures": { "0": "lamp", "1": "block/stone", "particle": "lamp" },
                 "elements": [{ "from": [0, 0, 0], "to": [16, 16, 16], "faces": { "north": { "uv": [0, 0, 8, 8], "texture": "#0" } } }] }""",
        )
        export.resolve("lamp.png").writeText("png")
        export.resolve("lamp.png.mcmeta").writeText("""{ "animation": {} }""")

        val written = files().use { it.import(export.resolve("lamp.json"), "nordtal:item/lamp") }

        val model = pack.resolve("src/assets/nordtal/models/item/lamp.json")
        val png = pack.resolve("src/assets/nordtal/textures/item/lamp.png")
        assertEquals(listOf(model, png, File(png.path + ".mcmeta")), written)
        val textures = (OxfmtJson.read(model) as Map<*, *>)["textures"]
        assertEquals(mapOf("0" to "nordtal:item/lamp", "1" to "block/stone", "particle" to "nordtal:item/lamp"), textures)
        assertEquals("png", png.readText())
        files().use { it.write("nordtal:lamp", model("""{ "type": "model", "model": "nordtal:item/lamp" }""")) }
    }

    @Test
    fun anImportRefusesATextureItCannotFindAndAFileThatExists() {
        val export = directory.resolve("export").apply { mkdirs() }
        export.resolve("lamp.json").writeText("""{ "textures": { "0": "glow" } }""")

        val missing = assertThrows<IllegalArgumentException> { files().use { it.import(export.resolve("lamp.json"), "nordtal:item/lamp") } }
        export.resolve("glow.png").writeText("png")
        files().use { it.import(export.resolve("lamp.json"), "nordtal:item/lamp") }
        val existing = assertThrows<IllegalStateException> { files().use { it.import(export.resolve("lamp.json"), "nordtal:item/lamp") } }

        assertEquals(
            "Texture 0 of lamp.json is glow, which is neither beside it as glow.png nor in the pack or the client jar.",
            missing.message,
        )
        assertEquals("src/assets/nordtal/models/item/lamp.json exists already.", existing.message)
    }

    @Test
    fun everyItemDefinitionOfTheClientJarPassesAndIsWrittenBackUnchanged() {
        val version = MinecraftVersion.of(System.getProperty("nordtal.paperVersion"))
        val client = VanillaClient.jar(version, URI(VanillaClient.MANIFEST), File(System.getProperty("nordtal.vanillaClientCache")))
        val items =
            ZipFile(client).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { it.name.startsWith("assets/minecraft/items/") && it.name.endsWith(".json") }
                    .associate { it.name to zip.getInputStream(it).use { input -> input.readBytes().decodeToString() } }
            }
        assertTrue(items.size > 1000, "${items.size} item definitions")

        val failures =
            ItemFiles(pack) { client }.use { files ->
                items.mapNotNull { (name, text) ->
                    val definition = OxfmtJson.read(text)
                    val problems = ItemDefinitions.problems(definition) + ItemDefinitions.missing(definition, files::read, files::sprite)
                    when {
                        problems.isNotEmpty() -> "$name: $problems"
                        OxfmtJson.read(OxfmtJson.format(definition)) != definition -> "$name is not written back unchanged"
                        else -> null
                    }
                }
            }

        assertEquals(emptyList<String>(), failures.take(10))
    }
}
