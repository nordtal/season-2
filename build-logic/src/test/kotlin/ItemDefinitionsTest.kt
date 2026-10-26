package eu.nordtal.season.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

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
        assertEquals(emptyList<String>(), files().use { it.describe("nordtal:example", emptySet())["problems"] })
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
    fun aValueOfAnotherKindIsNamedWithItsPlace() {
        val definition =
            OxfmtJson.read(
                model(
                    """{ "type": "minecraft:range_dispatch", "property": "minecraft:damage", "normalize": "yes",
                         "entries": [{ "threshold": "1,5", "model": { "type": "minecraft:model", "model": 7 } }] }""",
                ),
            )

        assertEquals(
            listOf(
                "model > normalize is no boolean: yes",
                "model > entries[0] > threshold is no number: 1,5",
                "model > entries[0] > model > model is no id: 7",
            ),
            ItemDefinitions.problems(definition),
        )
    }

    @Test
    fun anEditRefusesAValueOfAnotherKindByItsField() {
        files().use {
            it.write(
                "nordtal:example",
                model(
                    """{ "type": "minecraft:range_dispatch", "property": "minecraft:damage", "entries": [{ "threshold": 1, "model": $apple }] }""",
                ),
            )
        }

        val refusal = assertThrows<FieldRefusal> { edited("model/entries/0", """{ "threshold": "1,5" }""") }

        assertEquals(listOf("threshold", "threshold is no number: 1,5"), listOf(refusal.field, refusal.message))
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

        val vanilla = files().use { it.describe("minecraft:apple", emptySet()) }
        val written = files().use { it.write("minecraft:apple", model("""{ "type": "minecraft:empty" }""")) }
        val override = files().use { it.describe("apple", emptySet()) }

        assertEquals(
            listOf("vanilla", null, OxfmtJson.read(model(apple))),
            listOf(vanilla["source"], vanilla["file"], vanilla["definition"]),
        )
        assertEquals(listOf("pack", written.absolutePath), listOf(override["source"], override["file"]))
    }

    /** Imports [source] as the model [id] into the model of the item `nordtal:lamp`, which draws an apple before. */
    private fun imported(
        source: File,
        id: String = "nordtal:item/lamp",
        at: String = "model",
    ): List<File> {
        if (!pack.resolve("src/assets/nordtal/items/lamp.json").isFile) files().use { it.write("nordtal:lamp", model(apple)) }
        return files().use { it.import(source, id, "nordtal:lamp", at) }
    }

    @Test
    fun aBlockbenchModelIsImportedWithItsTexturesUnderTheChosenNamespaceAndDrawnByTheItem() {
        val export = directory.resolve("export").apply { mkdirs() }
        export.resolve("lamp.json").writeText(
            """{ "credit": "Made with Blockbench", "texture_size": [32, 32],
                 "textures": { "0": "lamp", "1": "block/stone", "particle": "lamp" },
                 "elements": [{ "from": [0, 0, 0], "to": [16, 16, 16], "faces": { "north": { "uv": [0, 0, 8, 8], "texture": "#0" } } }] }""",
        )
        export.resolve("lamp.png").writeText("png")
        export.resolve("lamp.png.mcmeta").writeText("""{ "animation": {} }""")

        val written = imported(export.resolve("lamp.json"))

        val model = pack.resolve("src/assets/nordtal/models/item/lamp.json")
        val png = pack.resolve("src/assets/nordtal/textures/item/lamp.png")
        val item = pack.resolve("src/assets/nordtal/items/lamp.json")
        assertEquals(listOf(model, png, File(png.path + ".mcmeta"), item), written)
        val textures = (OxfmtJson.read(model) as Map<*, *>)["textures"]
        assertEquals(mapOf("0" to "nordtal:item/lamp", "1" to "block/stone", "particle" to "nordtal:item/lamp"), textures)
        assertEquals("png", png.readText())
        assertEquals(OxfmtJson.read(model("""{ "type": "minecraft:model", "model": "nordtal:item/lamp" }""")), OxfmtJson.read(item))
    }

    @Test
    fun anImportRefusesATextureItCannotFindAndAFileThatExists() {
        val export = directory.resolve("export").apply { mkdirs() }
        export.resolve("lamp.json").writeText("""{ "textures": { "0": "glow" } }""")

        val missing = assertThrows<IllegalArgumentException> { imported(export.resolve("lamp.json")) }
        export.resolve("glow.png").writeText("png")
        imported(export.resolve("lamp.json"))
        val existing = assertThrows<FieldRefusal> { imported(export.resolve("lamp.json")) }

        assertEquals(
            "Texture 0 of lamp.json is glow, which is neither beside it as glow.png nor in the pack or the client jar.",
            missing.message,
        )
        assertEquals(listOf("id", "src/assets/nordtal/models/item/lamp.json exists already."), listOf(existing.field, existing.message))
    }

    @Test
    fun anImportRefusesAModelOrTextureThatTheClientJarHas() {
        val export = directory.resolve("export").apply { mkdirs() }
        export.resolve("apple.json").writeText("""{ "textures": { "layer0": "stone" } }""")
        export.resolve("stone.png").writeText("png")

        val model = assertThrows<FieldRefusal> { imported(export.resolve("apple.json"), "minecraft:item/apple") }
        val texture = assertThrows<FieldRefusal> { imported(export.resolve("apple.json"), "minecraft:block/apple") }

        assertEquals("minecraft:models/item/apple.json is in the client jar, which the import would hide.", model.message)
        assertEquals("minecraft:textures/block/stone.png is in the client jar, which the import would hide.", texture.message)
        assertTrue(!pack.resolve("src/assets/minecraft").exists())
    }

    @Test
    fun anImportWhoseItemRefusesTheModelWritesNoFileAndCanBeRepeated() {
        val export = directory.resolve("export").apply { mkdirs() }
        export.resolve("lamp.json").writeText("""{ "textures": { "0": "lamp" } }""")
        export.resolve("lamp.png").writeText("png")

        assertThrows<IllegalArgumentException> { imported(export.resolve("lamp.json"), at = "model/cases/0") }
        val written = imported(export.resolve("lamp.json"))

        assertEquals(pack.resolve("src/assets/nordtal/models/item/lamp.json"), written[0])
    }

    @Test
    fun anImportWhoseTextureCannotBeCopiedWritesNoFile() {
        val export = directory.resolve("export").apply { mkdirs() }
        export.resolve("lamp.json").writeText("""{ "textures": { "0": "lamp" } }""")
        export.resolve("lamp.png").writeText("png")
        files().use { it.write("nordtal:lamp", model(apple)) }
        pack.resolve("src/assets/nordtal/textures").writeText("a file where the folder belongs")

        val refusal = assertThrows<IllegalStateException> { imported(export.resolve("lamp.json")) }

        assertTrue("so nothing was written" in refusal.message!!, refusal.message)
        assertTrue(!pack.resolve("src/assets/nordtal/models").exists())
    }

    @Test
    fun aBlockbenchTextureWithCapitalsIsImportedUnderItsNameInLowerCase() {
        val export = directory.resolve("export").apply { mkdirs() }
        export.resolve("lamp.json").writeText("""{ "textures": { "0": "Lantern" } }""")
        export.resolve("Lantern.png").writeText("png")

        val written = imported(export.resolve("lamp.json"))

        assertEquals(pack.resolve("src/assets/nordtal/textures/item/lantern.png"), written[1])
        assertEquals(mapOf("0" to "nordtal:item/lantern"), (OxfmtJson.read(written[0]) as Map<*, *>)["textures"])
    }

    /** A copy of the Blockbench project `blockbench/lamp.bbmodel` of the test resources, changed by [change]. */
    private fun project(change: (MutableMap<String, Any?>) -> Unit = {}): File {
        val text = javaClass.getResource("/blockbench/lamp.bbmodel")!!.readText()

        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parseText(text) as MutableMap<String, Any?>
        change(json)
        return directory.resolve("lamp.bbmodel").apply { writeText(JsonOutput.toJson(json)) }
    }

    @Test
    fun aBlockbenchProjectIsImportedAsTheModelAndTexturesThatBlockbenchExports() {
        val written = imported(project())

        val model = OxfmtJson.read(written[0])
        val expected =
            OxfmtJson.read(
                """{
                  "textures": { "0": "nordtal:item/lamp_base", "glow": "nordtal:item/lamp_glow", "particle": "nordtal:item/lamp_glow" },
                  "elements": [
                    { "from": [4, 0, 4], "to": [12, 2, 12], "shade": false, "light_emission": 7, "faces": {
                      "north": { "uv": [0, 0, 8, 2], "texture": "#0" },
                      "east": { "uv": [0, 2, 8, 4], "rotation": 180, "texture": "#0" },
                      "west": { "uv": [0, 2, 8, 4], "texture": "#0" },
                      "up": { "uv": [0, 0, 16, 16], "texture": "#glow", "tintindex": 0 },
                      "down": { "uv": [8, 8, 16, 16], "rotation": 90, "texture": "#0", "cullface": "down" } } },
                    { "from": [5, 2, 5], "to": [11, 9, 11], "rotation": { "angle": 22.5, "axis": "y", "origin": [8, 8, 8], "rescale": true },
                      "faces": { "north": { "uv": [0, 0, 12, 14], "texture": "#glow" } } },
                    { "from": [7, 9, 7], "to": [9, 11, 9], "rotation": { "x": 30, "y": 45, "z": 0, "origin": [8, 10, 8] },
                      "faces": { "up": { "uv": [0, 0, 4, 4], "texture": "#glow" } } }
                  ],
                  "display": {
                    "gui": { "rotation": [30, 225, 0], "translation": [0, 1, 0], "scale": [0.625, 0.625, 0.625] },
                    "head": { "translation": [0, 14, 0] }
                  }
                }""",
            )
        assertEquals(expected, model)
        val glow = pack.resolve("src/assets/nordtal/textures/item/lamp_glow.png")
        assertEquals(
            listOf(pack.resolve("src/assets/nordtal/textures/item/lamp_base.png"), glow, File(glow.path + ".mcmeta")),
            written.subList(1, 4),
        )
        assertEquals(listOf(16, 32), ImageIO.read(glow).let { listOf(it.width, it.height) })
        assertEquals(
            mapOf("animation" to mapOf("frametime" to 3, "interpolate" to true, "frames" to listOf(1, 0))),
            OxfmtJson.read(File(glow.path + ".mcmeta")),
        )
    }

    @Test
    fun aBlockbenchProjectOfAnotherFormatOrWithAMeshIsRefused() {
        val format =
            assertThrows<IllegalArgumentException> {
                imported(
                    project {
                        (it["meta"] as MutableMap<String, Any?>)["model_format"] =
                            "bedrock"
                    },
                )
            }
        val mesh =
            assertThrows<IllegalArgumentException> {
                imported(
                    project {
                        (it["elements"] as MutableList<Any?>) +=
                            mapOf("name" to "blob", "type" to "mesh", "vertices" to emptyMap<String, Any>())
                    },
                )
            }

        assertEquals(
            "lamp.bbmodel is a Blockbench project of the format bedrock. Only Java Block/Item models can be imported.",
            format.message,
        )
        assertEquals("Element blob of lamp.bbmodel is a mesh. A Java Block/Item model holds only cubes.", mesh.message)
        assertTrue(!pack.resolve("src/assets/nordtal/models/item/lamp.json").exists())
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.part(
        list: String,
        index: Int,
    ) = (this[list] as List<Any?>)[index] as MutableMap<String, Any?>

    @Test
    fun aBlockbenchTextureWhoseNameNoTexturePathCanHoldIsRefusedByItsName() {
        val space = assertThrows<IllegalArgumentException> { imported(project { it.part("textures", 0)["name"] = "Lamp Texture.png" }) }
        val up = assertThrows<IllegalArgumentException> { imported(project { it.part("textures", 0)["name"] = "../x.png" }) }

        fun refusal(name: String) =
            "Texture $name of lamp.bbmodel cannot be imported under its name. Rename it in Blockbench to a name of only " +
                "a-z, 0-9, _, - and . that does not end in ."
        assertEquals(listOf(refusal("Lamp Texture"), refusal("../x")), listOf(space.message, up.message))
        assertTrue(!pack.resolve("src/assets/nordtal/models").exists())
    }

    @Test
    fun twoBlockbenchTexturesThatWouldBeTheSameFileAreRefused() {
        val same = assertThrows<IllegalArgumentException> { imported(project { it.part("textures", 1)["name"] = "lamp_base.png" }) }
        val case = assertThrows<IllegalArgumentException> { imported(project { it.part("textures", 1)["name"] = "Lamp_Base.png" }) }

        assertEquals("Two textures of lamp.bbmodel are named lamp_base.", same.message)
        assertEquals("Textures lamp_base and Lamp_Base of lamp.bbmodel would both be nordtal:item/lamp_base.", case.message)
        assertTrue(!pack.resolve("src/assets/nordtal/models").exists())
    }

    @Test
    fun aBlockbenchElementOutsideTheSpaceOfAJavaModelIsRefusedByItsName() {
        val refusal =
            assertThrows<IllegalArgumentException> {
                imported(
                    project {
                        it.part("elements", 2)["inflate"] = 30
                    },
                )
            }

        assertEquals(
            "Element tilted of lamp.bbmodel leaves the space from -16 to 32 that a Java Block/Item model allows.",
            refusal.message,
        )
    }

    @Test
    fun aBlockbenchImportClampsTheLightAndLeavesOutAnElementThatIsNotExported() {
        val written =
            imported(
                project {
                    it.part("elements", 0)["light_emission"] = 20
                    it.part("elements", 1)["export"] = false
                    it.part("elements", 2)["export"] = false
                },
            )

        val elements = (OxfmtJson.read(written[0]) as Map<*, *>)["elements"] as List<*>
        assertEquals(listOf(15), elements.map { (it as Map<*, *>)["light_emission"] })
    }

    @Test
    fun aBlockbenchFaceWithoutUvIsRefusedByItsElement() {
        @Suppress("UNCHECKED_CAST")
        val refusal =
            assertThrows<IllegalArgumentException> {
                imported(project { ((it.part("elements", 1)["faces"] as Map<*, *>)["north"] as MutableMap<String, Any?>).remove("uv") })
            }

        assertEquals("Face north of element glass of lamp.bbmodel has no uv.", refusal.message)
    }

    @Test
    fun aDefinitionWhoseReferencesAreAllInThePackNeverOpensTheClientJar() {
        pack.resolve("src/assets/nordtal/models/item/wand.json").apply { parentFile.mkdirs() }.writeText(
            """{ "textures": { "layer0": "nordtal:item/wand" } }""",
        )
        pack.resolve("src/assets/nordtal/textures/item/wand.png").apply { parentFile.mkdirs() }.writeText("png")

        val written =
            ItemFiles(pack) { throw AssertionError("the client jar was opened") }.use {
                it.write("nordtal:wand", model("""{ "type": "model", "model": "nordtal:item/wand" }"""))
            }

        assertEquals(
            """
            {
                "model": {
                    "type": "model",
                    "model": "nordtal:item/wand"
                }
            }

            """.trimIndent(),
            written.readText(),
        )
    }

    @Test
    fun theTypesNameEachFieldWithItsKindAndWhetherItIsRequired() {
        fun field(
            name: String,
            kind: String,
            required: Boolean = false,
        ) = mapOf("name" to name, "kind" to kind, "required" to required)

        fun entry(
            table: String,
            type: String,
        ) = (ItemDefinitions.types[table] as List<*>).map { it as Map<*, *> }.single { it["type"] == type }

        assertEquals(
            listOf(
                field("model", "model", true),
                field("hand_animation_on_swap", "boolean"),
                field("oversized_in_gui", "boolean"),
                field("swap_animation_scale", "number"),
            ),
            ItemDefinitions.types["item"],
        )
        assertEquals(listOf(field("when", "json", true), field("model", "model", true)), ItemDefinitions.types["case"])
        assertEquals(
            mapOf(
                "type" to "minecraft:select",
                "properties" to "select",
                "fields" to
                    listOf(
                        field("property", "property", true),
                        field("cases", "cases", true),
                        field("fallback", "model"),
                        field("transformation", "json"),
                    ),
            ),
            entry("models", "minecraft:select"),
        )
        assertEquals(listOf(field("wobble", "boolean"), field("source", "text", true)), entry("range_dispatch", "minecraft:time")["fields"])
        assertEquals(
            listOf(field("model", "special", true), field("base", "id", true), field("transformation", "json")),
            entry("models", "minecraft:special")["fields"],
        )
    }

    @Test
    fun aTextureOfAnItemIsADraftWhenItsPngIsAChangedFile() {
        pack.resolve("src/assets/nordtal/models/item/wand.json").apply { parentFile.mkdirs() }.writeText(
            """{ "parent": "minecraft:item/generated", "textures": { "layer0": "nordtal:item/wand" } }""",
        )
        val png = pack.resolve("src/assets/nordtal/textures/item/wand.png").apply { parentFile.mkdirs() }
        ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "png", png)
        files().use { it.write("nordtal:wand", model("""{ "type": "minecraft:model", "model": "nordtal:item/wand" }""")) }

        val models = files().use { it.describe("nordtal:wand", setOf("src/assets/nordtal/textures/item/wand.png")) }["models"]

        assertEquals(
            true,
            (((models as List<*>).single() as Map<*, *>)["textures"] as List<*>)
                .single()
                .let {
                    it as Map<*, *>
                }["texture"]
                .let { it as Map<*, *> }["draft"],
        )
    }

    @Test
    fun anAnimatedTextureOfAnItemIsDescribedWithItsMcmetaAndFrames() {
        pack.resolve("src/assets/nordtal/models/item/wand.json").apply { parentFile.mkdirs() }.writeText(
            """{ "parent": "minecraft:item/generated", "textures": { "layer0": "nordtal:item/wand" } }""",
        )
        val png = pack.resolve("src/assets/nordtal/textures/item/wand.png").apply { parentFile.mkdirs() }
        ImageIO.write(BufferedImage(2, 4, BufferedImage.TYPE_INT_ARGB), "png", png)
        File(png.path + ".mcmeta").writeText("""{ "animation": { "frametime": 3 } }""")
        files().use { it.write("nordtal:wand", model("""{ "type": "minecraft:model", "model": "nordtal:item/wand" }""")) }

        val models = files().use { it.describe("nordtal:wand", emptySet()) }["models"] as List<*>
        val texture = ((models.single() as Map<*, *>)["textures"] as List<*>).single() as Map<*, *>

        assertEquals("src/assets/nordtal/textures/item/wand.png.mcmeta", texture["mcmeta"])
        assertEquals(mapOf("frametime" to 3), texture["animation"])
        assertEquals(listOf(0, 2), (texture["frames"] as List<*>).map { (it as Map<*, *>)["y"] })
    }

    @Test
    fun anItemIsDescribedWithTheTexturesOfEachModelItDraws() {
        pack.resolve("src/assets/nordtal/models/item/wand.json").apply { parentFile.mkdirs() }.writeText(
            """{ "parent": "minecraft:item/generated", "textures": { "layer0": "nordtal:item/wand", "particle": "#layer0" } }""",
        )
        val png = pack.resolve("src/assets/nordtal/textures/item/wand.png").apply { parentFile.mkdirs() }
        ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "png", png)
        files().use {
            it.write(
                "nordtal:wand",
                model("""{ "type": "minecraft:composite", "models": [{ "type": "model", "model": "nordtal:item/wand" }, $apple] }"""),
            )
        }

        val models = files().use { it.describe("nordtal:wand", emptySet()) }["models"]

        assertEquals(
            listOf(
                mapOf(
                    "model" to "nordtal:item/wand",
                    "textures" to
                        listOf(
                            mapOf(
                                "key" to "layer0",
                                "texture" to
                                    mapOf(
                                        "id" to "nordtal:item/wand.png",
                                        "png" to "src/assets/nordtal/textures/item/wand.png",
                                        "aseprite" to "art/assets/nordtal/textures/item/wand.aseprite",
                                        "asepriteExists" to false,
                                        "draft" to false,
                                        "width" to 2,
                                        "height" to 3,
                                    ),
                                "mcmeta" to null,
                                "animation" to null,
                                "frames" to null,
                            ),
                        ),
                ),
                mapOf(
                    "model" to "minecraft:item/apple",
                    "textures" to listOf(mapOf("key" to "layer0", "texture" to mapOf("id" to "minecraft:item/apple.png"))),
                ),
            ),
            models,
        )
    }

    private val select =
        model(
            """{ "type": "minecraft:select", "property": "minecraft:display_context",
                 "cases": [{ "when": "gui", "model": $apple }], "fallback": $apple }""",
        )

    private fun edited(
        at: String,
        fields: String? = null,
        add: String? = null,
        remove: Boolean = false,
    ): Any? {
        val file = files().use { it.edit("nordtal:example", at, fields, add, remove) }
        return (OxfmtJson.read(file) as Map<*, *>)["model"]
    }

    @Test
    fun theDesignerSpecsReadTheTypesThatReadItemPrints() {
        val fixture = File(System.getProperty("nordtal.itemTypes"))

        assertEquals(JsonSlurper().parseText(JsonOutput.toJson(ItemDefinitions.types)), JsonSlurper().parse(fixture))
    }

    @Test
    fun anEditNamesEachRequiredValueThatItStillLacks() {
        files().use { it.write("nordtal:example", model(apple)) }

        val type = assertThrows<FieldsNeeded> { edited("model", """{ "type": "minecraft:select", "property": "minecraft:block_state" }""") }
        files().use { it.write("nordtal:example", select) }
        val case = assertThrows<FieldsNeeded> { edited("model", "{}", add = "cases") }

        assertEquals(mapOf("block_state_property" to "text"), type.fields)
        assertEquals(mapOf("when" to "json"), case.fields)
    }

    @Test
    fun anEditSetsAFieldAtItsPlaceAndKeepsTheRest() {
        files().use { it.write("nordtal:example", select) }

        val model = edited("model/cases/0", """{ "when": ["gui", "ground"] }""")

        assertEquals(
            OxfmtJson.read(
                """{ "type": "minecraft:select", "property": "minecraft:display_context",
                     "cases": [{ "when": ["gui", "ground"], "model": $apple }], "fallback": $apple }""",
            ),
            model,
        )
    }

    @Test
    fun aNewTypeDropsTheFieldsItDoesNotAllowAndStartsEachModelItRequiresEmpty() {
        files().use { it.write("nordtal:example", select) }

        val model = edited("model", """{ "type": "minecraft:condition" }""")

        assertEquals(
            OxfmtJson.read(
                """{ "type": "minecraft:condition", "property": "minecraft:custom_model_data",
                     "on_true": { "type": "minecraft:empty" }, "on_false": { "type": "minecraft:empty" } }""",
            ),
            model,
        )
    }

    @Test
    fun aNewTypeDropsAFieldOfTheSameNameAndAnotherKind() {
        files().use { it.write("nordtal:example", model(apple)) }

        val model = edited("model", """{ "type": "minecraft:special", "base": "minecraft:item/apple" }""")

        assertEquals(
            OxfmtJson.read(
                """{ "type": "minecraft:special", "base": "minecraft:item/apple", "model": { "type": "minecraft:bell" } }""",
            ),
            model,
        )
    }

    @Test
    fun aNewPropertyDropsTheFieldsOfTheOldOne() {
        files().use {
            it.write(
                "nordtal:example",
                model("""{ "type": "minecraft:select", "property": "minecraft:local_time", "pattern": "MM-dd", "cases": [] }"""),
            )
        }

        val model = edited("model", """{ "property": "minecraft:component", "component": "minecraft:custom_name" }""")

        assertEquals(
            OxfmtJson.read(
                """{ "type": "minecraft:select", "property": "minecraft:component", "cases": [], "component": "minecraft:custom_name" }""",
            ),
            model,
        )
    }

    @Test
    fun anAddedCaseEntryOrTintStartsWithTheGivenFieldsAndAnEmptyModel() {
        files().use { it.write("nordtal:example", select) }

        val cases = (edited("model", """{ "when": "ground" }""", add = "cases") as Map<*, *>)["cases"] as List<*>
        val tints = (edited("model/fallback", """{ "type": "minecraft:constant", "value": -1 }""", add = "tints") as Map<*, *>)

        assertEquals(OxfmtJson.read("""{ "when": "ground", "model": { "type": "minecraft:empty" } }"""), cases[1])
        assertEquals(OxfmtJson.read("""[{ "type": "minecraft:constant", "value": -1 }]"""), (tints["fallback"] as Map<*, *>)["tints"])
    }

    @Test
    fun anAddedFallbackStartsAsAnEmptyModelAndOnlyWhereThereIsNone() {
        files().use { it.write("nordtal:example", select) }
        edited("model/fallback", remove = true)

        val model = edited("model", add = "fallback") as Map<*, *>

        assertEquals(OxfmtJson.read("""{ "type": "minecraft:empty" }"""), model["fallback"])
        assertThrows<IllegalArgumentException> { edited("model", add = "fallback") }
    }

    @Test
    fun aRemovedCaseLeavesItsListAndARemovedFallbackItsModel() {
        files().use { it.write("nordtal:example", select) }

        edited("model/cases/0", remove = true)
        val model = edited("model/fallback", remove = true)

        assertEquals(
            OxfmtJson.read("""{ "type": "minecraft:select", "property": "minecraft:display_context", "cases": [] }"""),
            model,
        )
    }

    @Test
    fun anEditThatBreaksARuleIsRefusedAndWritesNothing() {
        val file = files().use { it.write("nordtal:example", select) }
        val before = file.readText()

        val refusal = assertThrows<IllegalStateException> { edited("model/fallback", """{ "model": "nordtal:item/none" }""") }
        val nowhere = assertThrows<IllegalArgumentException> { edited("model/cases/3", """{ "when": "gui" }""") }

        assertEquals(
            "The item nordtal:example breaks 1 rule(s):\n    model nordtal:item/none is in neither the pack nor the client jar",
            refusal.message,
        )
        assertEquals("The item nordtal:example has nothing at model/cases/3.", nowhere.message)
        assertEquals(before, file.readText())
    }

    @Test
    fun anEditOfAVanillaItemWritesItsOverrideIntoThePack() {
        ZipOutputStream(directory.resolve("client.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/minecraft/items/apple.json"))
            zip.write(model(apple).toByteArray())
            zip.putNextEntry(ZipEntry("assets/minecraft/models/item/apple.json"))
            zip.write("{}".toByteArray())
        }

        val written = files().use { it.edit("minecraft:apple", "model", """{ "type": "minecraft:empty" }""", null, false) }

        assertEquals(pack.resolve("src/assets/minecraft/items/apple.json"), written)
        assertEquals(OxfmtJson.read(model("""{ "type": "minecraft:empty" }""")), OxfmtJson.read(written))
    }

    @Test
    fun everyItemDefinitionOfTheClientJarPassesAndIsWrittenBackUnchanged() {
        val version = MinecraftVersion.of(System.getProperty("nordtal.paperVersion"))
        // Loom keeps the jar it downloaded for :bridge-mod there. Before Loom ran once, or after Loom moves that file, the round
        // trip is skipped, not failed.
        val client = File(System.getProperty("nordtal.loomCache"), "$version/minecraft-client.jar")
        Assumptions.assumeTrue(client.isFile, "Fabric Loom has not downloaded the client jar of $version yet: $client")
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
                        problems.isNotEmpty() -> {
                            "$name: $problems"
                        }

                        // JsonOutput keeps the order of each object, so a key that moves fails too.
                        JsonOutput.toJson(OxfmtJson.read(OxfmtJson.asset(definition))) != JsonOutput.toJson(definition) -> {
                            "$name is not written back unchanged"
                        }

                        else -> {
                            null
                        }
                    }
                }
            }

        assertEquals(emptyList<String>(), failures.take(10))
    }
}
