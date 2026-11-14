package eu.nordtal.season.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class SoundsTest {
    @TempDir
    lateinit var root: File

    private val tone: File by lazy {
        root.resolve("tone.ogg").also { file ->
            SoundsTest::class.java.getResourceAsStream("/tone.ogg")!!.use { file.writeBytes(it.readBytes()) }
        }
    }

    private val vanillaEvents =
        """{"block.anvil.land": {"sounds": [{"name": "random/anvil_land", "volume": 0.5}], "subtitle": "subtitles.block.anvil.land"}}"""

    /** Vanilla's sounds from an asset index with one event, whose one sound is the test tone, and a log of each download. */
    private fun vanilla(
        downloads: MutableList<String> = mutableListOf(),
        events: String = vanillaEvents,
        extra: String? = null,
    ): VanillaSounds {
        fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        val objects =
            mapOf(
                "minecraft/sounds.json" to events.toByteArray(),
                "minecraft/sounds/random/anvil_land.ogg" to tone.readBytes(),
            ) + listOfNotNull(extra).associateWith { tone.readBytes() + 0 }
        val index =
            """{"objects": {${objects.entries.joinToString { (path, bytes) ->
                """"$path": {"hash": "${sha1(bytes)}", "size": ${bytes.size}}"""
            }}}}"""
        val served =
            objects.values.associateBy { "https://resources.download.minecraft.net/${sha1(it).take(2)}/${sha1(it)}" } +
                ("https://piston-meta.example/34.json" to index.toByteArray())
        val properties = root.resolve("vanilla-asset-index.properties")
        properties.writeText("id=34\nurl=https\\://piston-meta.example/34.json\nsha1=${sha1(index.toByteArray())}\n")
        return VanillaSounds(properties, root.resolve("cache")) { url ->
            downloads += url
            served.getValue(url)
        }
    }

    private fun clone() = DraftClone(root.resolve("repo"), "star@FE000")

    private fun soundsJson(
        clone: DraftClone,
        namespace: String = "nordtal",
    ) = OxfmtJson.read(clone.pack.resolve("src/assets/$namespace/sounds.json")) as Map<*, *>

    @Test
    fun aNewSoundEventTakesItsOggFileToTheEventsPathInItsNamespace() {
        val clone = clone()

        val written = Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)

        val ogg = clone.pack.resolve("src/assets/nordtal/sounds/ui/click.ogg")
        assertEquals(listOf(clone.pack.resolve("src/assets/nordtal/sounds.json"), ogg), written)
        assertTrue(ogg.readBytes().contentEquals(tone.readBytes()))
        assertEquals(mapOf("ui.click" to mapOf("sounds" to listOf(mapOf("name" to "nordtal:ui/click")))), soundsJson(clone))
    }

    @Test
    fun aFileThatIsNoOggVorbisIsRefusedWithWhatToDo() {
        val clone = clone()
        val mp3 = root.resolve("click.mp3").apply { writeBytes(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 0)) }

        val refusal = assertThrows<FieldRefusal> { Sounds.create(clone.pack, "nordtal:ui.click", mp3.absolutePath) }

        assertEquals("file", refusal.field)
        assertTrue(refusal.message!!.startsWith("click.mp3 is no Ogg Vorbis file"), refusal.message)
        assertFalse(clone.pack.resolve("src/assets/nordtal/sounds.json").exists())
    }

    @Test
    fun aVanillaEventIsOverriddenAndNotCreated() {
        val clone = clone()

        assertEquals(
            "sound",
            assertThrows<FieldRefusal> { Sounds.create(clone.pack, "minecraft:block.anvil.land", tone.absolutePath) }.field,
        )
        assertEquals(
            "sound",
            assertThrows<FieldRefusal> { Sounds.override(clone.pack, ::vanilla, "minecraft:block.nothing", true, null) }.field,
        )
    }

    @Test
    fun anOverrideThatReplacesStartsFromCopiesOfVanillasSoundsAndItsSubtitle() {
        val clone = clone()

        val written = Sounds.override(clone.pack, ::vanilla, "minecraft:block.anvil.land", true, null)

        val copy = clone.pack.resolve("src/assets/minecraft/sounds/nordtal/block/anvil/land/anvil_land.ogg")
        assertEquals(listOf(clone.pack.resolve("src/assets/minecraft/sounds.json"), copy), written)
        assertTrue(copy.readBytes().contentEquals(tone.readBytes()))
        assertEquals(
            mapOf(
                "block.anvil.land" to
                    mapOf(
                        "replace" to true,
                        "sounds" to
                            listOf(mapOf("name" to "minecraft:nordtal/block/anvil/land/anvil_land", "volume" to 0.5.toBigDecimal())),
                        "subtitle" to "subtitles.block.anvil.land",
                    ),
            ),
            soundsJson(clone, "minecraft"),
        )
    }

    @Test
    fun twoOverridesOfOneVanillaSoundEachTakeACopyOfTheirOwn() {
        val clone = clone()
        val events =
            """{"block.anvil.land": {"sounds": ["random/anvil_land"]}, "block.anvil.fall": {"sounds": ["random/anvil_land"]}}"""

        Sounds.override(clone.pack, { vanilla(events = events) }, "minecraft:block.anvil.land", true, null)
        Sounds.override(clone.pack, { vanilla(events = events) }, "minecraft:block.anvil.fall", true, null)

        val json = soundsJson(clone, "minecraft")
        assertEquals(listOf("minecraft:nordtal/block/anvil/land/anvil_land"), (json["block.anvil.land"] as Map<*, *>)["sounds"])
        assertEquals(listOf("minecraft:nordtal/block/anvil/fall/anvil_land"), (json["block.anvil.fall"] as Map<*, *>)["sounds"])
        val copy = clone.pack.resolve("src/assets/minecraft/sounds/nordtal/block/anvil/fall/anvil_land.ogg")
        assertTrue(copy.readBytes().contentEquals(tone.readBytes()))
    }

    @Test
    fun twoVanillaSoundsOfOneFileNameTakeTwoCopies() {
        val clone = clone()
        val events = """{"block.anvil.land": {"sounds": ["random/anvil_land", "other/anvil_land"]}}"""

        Sounds.override(clone.pack, {
            vanilla(events = events, extra = "minecraft/sounds/other/anvil_land.ogg")
        }, "minecraft:block.anvil.land", true, null)

        assertEquals(
            listOf("minecraft:nordtal/block/anvil/land/anvil_land", "minecraft:nordtal/block/anvil/land/anvil_land_2"),
            (soundsJson(clone, "minecraft")["block.anvil.land"] as Map<*, *>)["sounds"],
        )
        assertTrue(clone.pack.resolve("src/assets/minecraft/sounds/nordtal/block/anvil/land/anvil_land_2.ogg").isFile)
    }

    @Test
    fun anOverrideWhoseVanillaSoundIsMissingIsRefusedAndWritesNothing() {
        val clone = clone()
        val events = """{"block.anvil.land": {"sounds": ["random/anvil_land", "random/anvil_gone"]}}"""

        val refusal =
            assertThrows<FieldRefusal> {
                Sounds.override(clone.pack, { vanilla(events = events) }, "minecraft:block.anvil.land", true, null)
            }

        assertEquals("sound", refusal.field)
        assertFalse(clone.pack.resolve("src/assets/minecraft").exists())
    }

    @Test
    fun anOverrideKeepsASoundThatPlaysAnotherEventAsItIs() {
        val clone = clone()
        val events = """{"block.anvil.land": {"sounds": [{"name": "block.anvil.fall", "type": "event"}]}}"""

        val written = Sounds.override(clone.pack, { vanilla(events = events) }, "minecraft:block.anvil.land", true, null)

        assertEquals(listOf(clone.pack.resolve("src/assets/minecraft/sounds.json")), written)
        assertEquals(
            listOf(mapOf("name" to "minecraft:block.anvil.fall", "type" to "event")),
            (soundsJson(clone, "minecraft")["block.anvil.land"] as Map<*, *>)["sounds"],
        )
    }

    @Test
    fun anOverrideThatKeepsVanillasSoundsAddsItsFileBesideThemOutsideVanillasFolders() {
        val clone = clone()

        assertEquals(
            mapOf("file" to "sound"),
            assertThrows<FieldsNeeded> { Sounds.override(clone.pack, ::vanilla, "minecraft:block.anvil.land", false, null) }.fields,
        )
        Sounds.override(clone.pack, ::vanilla, "minecraft:block.anvil.land", false, tone.absolutePath)

        assertEquals(
            mapOf("block.anvil.land" to mapOf("sounds" to listOf(mapOf("name" to "minecraft:nordtal/block/anvil/land")))),
            soundsJson(clone, "minecraft"),
        )
        assertTrue(clone.pack.resolve("src/assets/minecraft/sounds/nordtal/block/anvil/land.ogg").isFile)
    }

    @Test
    fun theFieldsOfASoundAreSetAndClearedAndAWrongValueIsRefused() {
        val clone = clone()
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)

        Sounds.edit(
            clone.pack,
            ::vanilla,
            "nordtal:ui.click",
            0,
            """{"volume": 0.5, "pitch": 2.0, "stream": true}""",
            null,
            null,
            false,
            null,
        )
        Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.click", 0, """{"stream": null}""", null, null, false, null)
        val refusal =
            assertThrows<FieldRefusal> {
                Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.click", 0, """{"weight": 0}""", null, null, false, null)
            }

        assertEquals("fields", refusal.field)
        assertEquals("weight must be a whole number from 1.", refusal.message)
        assertEquals(
            listOf(mapOf("name" to "nordtal:ui/click", "volume" to 0.5.toBigDecimal(), "pitch" to "2.0".toBigDecimal())),
            (soundsJson(clone)["ui.click"] as Map<*, *>)["sounds"],
        )
        assertTrue(
            clone.pack
                .resolve("src/assets/nordtal/sounds.json")
                .readText()
                .contains(""""pitch": 2.0"""),
        )
    }

    @Test
    fun aSecondFileTakesTheNextPathAndARemovedSoundTakesItsFileAlong() {
        val clone = clone()
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)

        Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.click", null, null, tone.absolutePath, null, false, null)
        Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.click", null, null, null, "random/anvil_land", false, null)
        assertEquals(
            listOf(
                mapOf("name" to "nordtal:ui/click"),
                mapOf("name" to "nordtal:ui/click_2"),
                mapOf("name" to "minecraft:random/anvil_land"),
            ),
            (soundsJson(clone)["ui.click"] as Map<*, *>)["sounds"],
        )
        val removed = Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.click", 0, null, null, null, true, null)

        assertEquals(
            listOf(clone.pack.resolve("src/assets/nordtal/sounds.json"), clone.pack.resolve("src/assets/nordtal/sounds/ui/click.ogg")),
            removed,
        )
        assertFalse(clone.pack.resolve("src/assets/nordtal/sounds/ui/click.ogg").exists())
        assertEquals(
            "name",
            assertThrows<FieldRefusal> {
                Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.click", null, null, null, "random/nothing", false, null)
            }.field,
        )
    }

    @Test
    fun aSubtitleIsATextOfTheLanguageFilesUnderTheEventsKey() {
        val clone = clone()
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)

        Sounds.subtitle(clone.pack, "nordtal:ui.click", "en_us", "Button clicks", false)
        Sounds.subtitle(clone.pack, "nordtal:ui.click", "de_de", "Knopf klickt", false)

        assertEquals("subtitles.nordtal.ui.click", (soundsJson(clone)["ui.click"] as Map<*, *>)["subtitle"])
        assertEquals(
            mapOf(
                "src/assets/minecraft/lang/de_de.json" to mapOf("subtitles.nordtal.ui.click" to "Knopf klickt"),
                "src/assets/minecraft/lang/en_us.json" to mapOf("subtitles.nordtal.ui.click" to "Button clicks"),
            ),
            Texts.work(clone.pack),
        )

        Sounds.subtitle(clone.pack, "nordtal:ui.click", null, null, true)

        assertEquals(null, (soundsJson(clone)["ui.click"] as Map<*, *>)["subtitle"])
        assertEquals(emptyMap<String, Any>(), Texts.work(clone.pack))
    }

    @Test
    fun aDeletedEventTakesItsFilesAndSubtitleAlong() {
        val clone = clone()
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)
        Sounds.create(clone.pack, "nordtal:ui.close", tone.absolutePath)
        Sounds.subtitle(clone.pack, "nordtal:ui.click", "en_us", "Button clicks", false)

        Sounds.delete(clone.pack, "nordtal:ui.click")

        assertEquals(listOf("ui.close"), soundsJson(clone).keys.toList())
        assertFalse(clone.pack.resolve("src/assets/nordtal/sounds/ui/click.ogg").exists())
        assertEquals(emptyMap<String, Any>(), Texts.work(clone.pack))
    }

    @Test
    fun aSubtitleOfAVanillaEventNeedsReplaceAndTakesAKeyOfThePacksOwn() {
        val clone = clone()
        Sounds.override(clone.pack, ::vanilla, "minecraft:block.anvil.land", false, tone.absolutePath)

        val refusal =
            assertThrows<FieldRefusal> { Sounds.subtitle(clone.pack, "minecraft:block.anvil.land", "en_us", "Clang", false) }
        assertEquals("sound", refusal.field)

        Sounds.edit(clone.pack, ::vanilla, "minecraft:block.anvil.land", null, null, null, null, false, "true")
        assertEquals("subtitles.block.anvil.land", (soundsJson(clone, "minecraft")["block.anvil.land"] as Map<*, *>)["subtitle"])
        Sounds.subtitle(clone.pack, "minecraft:block.anvil.land", "en_us", "Clang", false)

        assertEquals(
            "subtitles.minecraft.block.anvil.land",
            (soundsJson(clone, "minecraft")["block.anvil.land"] as Map<*, *>)["subtitle"],
        )
        assertEquals(
            mapOf("src/assets/minecraft/lang/en_us.json" to mapOf("subtitles.minecraft.block.anvil.land" to "Clang")),
            Texts.work(clone.pack),
        )
    }

    @Test
    fun anOverrideThatStopsReplacingDropsVanillasCopiesAndItsSubtitle() {
        val clone = clone()
        Sounds.override(clone.pack, ::vanilla, "minecraft:block.anvil.land", true, null)
        Sounds.edit(clone.pack, ::vanilla, "minecraft:block.anvil.land", null, null, tone.absolutePath, null, false, null)
        Sounds.subtitle(clone.pack, "minecraft:block.anvil.land", "en_us", "Clang", false)

        Sounds.edit(clone.pack, ::vanilla, "minecraft:block.anvil.land", null, null, null, null, false, "false")

        assertEquals(
            mapOf("block.anvil.land" to mapOf("sounds" to listOf(mapOf("name" to "minecraft:nordtal/block/anvil/land")))),
            soundsJson(clone, "minecraft"),
        )
        assertFalse(clone.pack.resolve("src/assets/minecraft/sounds/nordtal/block/anvil/land/anvil_land.ogg").exists())
        assertEquals(emptyMap<String, Any>(), Texts.work(clone.pack))
    }

    @Test
    fun anOverrideThatStopsReplacingKeepsACopyThatWasChanged() {
        val clone = clone()
        Sounds.override(clone.pack, ::vanilla, "minecraft:block.anvil.land", true, null)
        val copy = clone.pack.resolve("src/assets/minecraft/sounds/nordtal/block/anvil/land/anvil_land.ogg")
        copy.writeBytes(copy.readBytes() + 0)

        Sounds.edit(clone.pack, ::vanilla, "minecraft:block.anvil.land", null, null, null, null, false, "false")

        assertEquals(
            listOf(mapOf("name" to "minecraft:nordtal/block/anvil/land/anvil_land", "volume" to 0.5.toBigDecimal())),
            (soundsJson(clone, "minecraft")["block.anvil.land"] as Map<*, *>)["sounds"],
        )
        assertTrue(copy.isFile)
    }

    @Test
    fun anEventNameIsDottedWordsAndTheLastNewEventTakesItsFileAlong() {
        val clone = clone()
        for (event in listOf("nordtal:ui/click", "nordtal:ui..click", "nordtal:.click")) {
            assertEquals("sound", assertThrows<FieldRefusal> { Sounds.create(clone.pack, event, tone.absolutePath) }.field, event)
        }
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)

        Sounds.delete(clone.pack, "nordtal:ui.click")

        assertFalse(clone.pack.resolve("src/assets/nordtal/sounds.json").exists())
    }

    @Test
    fun aSoundEventIsOneDraftWithItsFilesAndSubtitleAndADiscardTakesItBack() {
        val clone = clone()
        Sounds.create(clone.pack, "nordtal:ui.close", tone.absolutePath)
        clone.commitAll("feat: close")
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)
        Sounds.subtitle(clone.pack, "nordtal:ui.click", "en_us", "Button clicks", false)
        Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.close", 0, """{"volume": 0.25}""", null, null, false, null)

        assertEquals(
            listOf(
                listOf(
                    "sound nordtal:ui.click",
                    "nordtal:ui.click",
                    "added",
                    listOf("src/assets/nordtal/sounds/ui/click.ogg"),
                    listOf("subtitles.nordtal.ui.click"),
                    listOf("nordtal:ui.click"),
                ),
                listOf(
                    "sound nordtal:ui.close",
                    "nordtal:ui.close",
                    "changed",
                    emptyList<String>(),
                    emptyList<String>(),
                    listOf("nordtal:ui.close"),
                ),
            ),
            Drafts.list(clone.pack).map { listOf(it.id, it.label, it.state, it.files, it.keys, it.sounds) },
        )

        Drafts.discard(clone.pack, "sound nordtal:ui.click")

        assertEquals(listOf("sound nordtal:ui.close"), Drafts.list(clone.pack).map { it.id })
        assertEquals(listOf("ui.close"), soundsJson(clone).keys.toList())
        assertFalse(clone.pack.resolve("src/assets/nordtal/sounds/ui/click.ogg").exists())
        assertEquals(emptyMap<String, Any>(), Texts.work(clone.pack))
    }

    @Test
    fun anEventThatMainChangedTooIsAConflictNamedByTheEvent() {
        val clone = clone()
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)
        clone.commitAll("feat: click")
        clone.git(clone.clone, "push", "-q", "origin", "main")
        clone.commitOnMain("feat: louder") { pack ->
            Sounds.edit(pack, ::vanilla, "nordtal:ui.click", 0, """{"volume": 0.9}""", null, null, false, null)
        }
        clone.git(clone.clone, "fetch", "-q", "origin")
        Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.click", 0, """{"volume": 0.2}""", null, null, false, null)

        val conflict = Drafts.list(clone.pack).single().conflict!!

        assertEquals(listOf("nordtal:ui.click"), conflict.fields.map { it.name })
        Drafts.resolve(clone.pack, "sound nordtal:ui.click", null, mapOf("nordtal:ui.click" to "main"))
        assertEquals(emptyList<String>(), Drafts.list(clone.pack).map { it.id })
    }

    @Test
    fun aPublishCarriesTheEventItsFileAndItsSubtitleAlone() {
        val clone = clone()
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)
        Sounds.subtitle(clone.pack, "nordtal:ui.click", "en_us", "Button clicks", false)
        Sounds.create(clone.pack, "nordtal:ui.close", tone.absolutePath)

        Publish(
            clone.clone,
            clone.pack,
            clone.gh.absolutePath,
        ) {}.publish(listOf("sound nordtal:ui.click"), "feat(resource-pack): buttons click")

        val branch =
            clone.git(clone.origin, "for-each-ref", "--format=%(refname:short)", "refs/heads/").lines().single {
                it.isNotBlank() &&
                    it != "main"
            }
        assertEquals(
            mapOf("ui.click" to mapOf("sounds" to listOf(mapOf("name" to "nordtal:ui/click")), "subtitle" to "subtitles.nordtal.ui.click")),
            OxfmtJson.read(clone.onOrigin(branch, "resource-pack/src/assets/nordtal/sounds.json")),
        )
        assertEquals(
            """{"subtitles.nordtal.ui.click":"Button clicks"}""",
            groovy.json.JsonOutput.toJson(OxfmtJson.read(clone.onOrigin(branch, "resource-pack/src/assets/minecraft/lang/en_us.json"))),
        )
        clone.onOrigin(branch, "resource-pack/src/assets/nordtal/sounds/ui/click.ogg")
        assertEquals(listOf("sound nordtal:ui.close"), Drafts.list(clone.pack).map { it.id })
    }

    @Test
    fun aSoundPlaysAsAWavAtItsVolumeAndPitch() {
        val clone = clone()
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)
        Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.click", 0, """{"pitch": 2.0}""", null, null, false, null)

        val wav = Sounds.play(clone.pack, ::vanilla, "nordtal:ui.click", 0).readBytes()
        val vanillaWav = Sounds.play(clone.pack, ::vanilla, "minecraft:block.anvil.land", null).readBytes()

        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(44100, header.getInt(24))
        assertEquals(22050, ByteBuffer.wrap(vanillaWav).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
        assertTrue(header.getInt(40) / 2 in 4000..5000, "${header.getInt(40) / 2} samples")
    }

    @Test
    fun vanillasIndexAndSoundsAreDownloadedOnceAndCheckedAgainstTheirHash() {
        val downloads = mutableListOf<String>()
        vanilla(downloads).sound("minecraft:random/anvil_land")
        val again = vanilla(downloads)

        assertEquals(setOf("minecraft:block.anvil.land"), again.events().keys)
        assertEquals(3, downloads.size)
        val bad = VanillaSounds(root.resolve("vanilla-asset-index.properties"), root.resolve("other")) { "{}".toByteArray() }
        assertThrows<IllegalStateException> { bad.events() }
    }

    @Test
    fun theModelListsEachEventWithItsSoundsAndSubtitles() {
        val clone = clone()
        Sounds.create(clone.pack, "nordtal:ui.click", tone.absolutePath)
        Sounds.edit(clone.pack, ::vanilla, "nordtal:ui.click", null, null, null, "random/anvil_land", false, null)
        Sounds.subtitle(clone.pack, "nordtal:ui.click", "en_us", "Button clicks", false)

        val model = PackModel.of(GlyphAllocation.read(clone.pack.resolve("glyphs.json")), clone.pack, Drafts.list(clone.pack))

        assertEquals(
            listOf(
                mapOf(
                    "id" to "nordtal:ui.click",
                    "replace" to false,
                    "subtitle" to "subtitles.nordtal.ui.click",
                    "subtitles" to mapOf("en_us" to "Button clicks"),
                    "sounds" to
                        listOf(
                            mapOf("name" to "nordtal:ui/click", "file" to "src/assets/nordtal/sounds/ui/click.ogg"),
                            mapOf("name" to "minecraft:random/anvil_land", "file" to null),
                        ),
                    "draft" to "sound nordtal:ui.click",
                    "review" to null,
                ),
            ),
            model["sounds"],
        )
    }

    @Test
    fun theCheckRefusesASoundsJsonTheClientWouldDrop() {
        val assets = root.resolve("assets")
        assets.resolve("nordtal/sounds").mkdirs()
        tone.copyTo(assets.resolve("nordtal/sounds/ok.ogg"))
        assets.resolve("nordtal/sounds/bad.ogg").writeText("ID3")
        assets.resolve("nordtal/sounds.json").writeText(
            """{"a": {"sounds": ["nordtal:ok", {"name": "nordtal:gone", "volume": 0, "weight": 1.5}]}, "b": {"sounds": [], "replace": "yes"}, "c": {"sounds": ["Nordtal:Up", {"name": "nordtal:ok", "pitch": null}]}}""",
        )

        assertEquals(
            listOf(
                "nordtal/sounds/bad.ogg is no Ogg Vorbis file, the one format Minecraft plays.",
                "nordtal/sounds.json: sound 1 of a: volume must be a number above 0.",
                "nordtal/sounds.json: sound 1 of a: weight must be a whole number from 1.",
                "nordtal/sounds.json: sound 1 of a names nordtal:gone, but the pack has no nordtal/sounds/gone.ogg.",
                "nordtal/sounds.json: replace of b must be true or false.",
                "nordtal/sounds.json: sound 0 of c needs a name such as nordtal:ui/click, in lower case.",
                "nordtal/sounds.json: sound 1 of c: pitch must not be null.",
            ),
            PackRules.findings(assets),
        )
    }
}
