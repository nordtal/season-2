package eu.nordtal.season.build

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConflictsTest {
    @TempDir
    lateinit var root: File

    private val star = "nordtal:badges/star.png"
    private val starPng = "src/assets/nordtal/textures/badges/star.png"
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()

    private fun publish(clone: DraftClone) = Publish(clone.clone, clone.pack, clone.gh.absolutePath) {}

    private fun fetch(clone: DraftClone) = clone.git(clone.clone, "fetch", "-q", "origin")

    private fun draft(
        clone: DraftClone,
        id: String = star,
    ) = Drafts.list(clone.pack).single { it.id == id }

    private fun glyph(clone: DraftClone) = clone.glyphOf(clone.pack.resolve("glyphs.json").readText(), "FE000")

    private fun blob(
        clone: DraftClone,
        file: File,
    ) = clone.git(clone.clone, "hash-object", "--", file.absolutePath)

    /** The star white in the clone and red on main. */
    private fun starOnBothSides(): DraftClone {
        val clone = DraftClone(root, "star@FE000", "moon@FE001")
        clone.png("star", white)
        clone.commitOnMain("feat: the star is red") { clone.png("star", red, it) }
        fetch(clone)
        return clone
    }

    /** The star's about text set in the clone and on main, each its own way, and its constant only in the clone. */
    private fun aboutOnBothSides(): DraftClone {
        val clone = DraftClone(root, "star@FE000")
        clone.editBadge(clone.pack, "FE000", "constant", "BIG_STAR")
        clone.editBadge(clone.pack, "FE000", "about", "Ours.")
        clone.commitOnMain("docs: the star is described") {
            clone.editBadge(it, "FE000", "about", "Theirs.")
            clone.editBadge(it, "FE000", "status", "keep")
        }
        fetch(clone)
        return clone
    }

    @Test
    fun aTextureThatChangedInTheCloneAndOnMainIsAConflictAndAnotherDraftIsNone() {
        val clone = starOnBothSides()
        clone.png("moon", white)

        assertEquals(Drafts.Conflict(listOf(starPng), emptyList()), draft(clone).conflict)
        assertNull(draft(clone, "nordtal:badges/moon.png").conflict)
    }

    @Test
    fun aFieldThatOnlyOneSideChangedMergesAndAFieldThatBothChangedIsAConflict() {
        val clone = aboutOnBothSides()

        assertEquals(
            Drafts.Conflict(emptyList(), listOf(Drafts.Field("about", "\"Ours.\"", "\"Theirs.\""))),
            draft(clone).conflict,
        )
    }

    @Test
    fun aDraftThatEqualsMainIsNoDraft() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", red)
        clone.commitOnMain("feat: the star is red") { clone.png("star", red, it) }
        fetch(clone)

        assertEquals(emptyList<String>(), Drafts.list(clone.pack).map { it.id })
    }

    @Test
    fun aPublishRefusesADraftWithAConflict() {
        val clone = starOnBothSides()

        val refusal =
            assertThrows<IllegalArgumentException> { publish(clone).publish(listOf(star), "feat(resource-pack): the star is white") }

        assertTrue("star" in refusal.message!!, refusal.message)
        assertFalse(clone.ghLog.exists() && clone.ghLog.readText().contains("pr create"))
    }

    @Test
    fun aPublishKeepsTheFieldsThatOnlyMainChanged() {
        val clone = DraftClone(root, "star@FE000")
        clone.editBadge(clone.pack, "FE000", "constant", "BIG_STAR")
        clone.commitOnMain("docs: the star is described") { clone.editBadge(it, "FE000", "about", "Theirs.") }

        publish(clone).publish(listOf(star), "feat(resource-pack): the star is big")

        val pushed = clone.glyphOf(clone.onOrigin("designer/the-star-is-big", "resource-pack/glyphs.json"), "FE000")
        assertEquals("BIG_STAR", pushed["constant"])
        assertEquals("Theirs.", pushed["about"])
    }

    @Test
    fun aResolveKeepsTheChosenVersionOfTheFilesAndOfEachField() {
        val clone = aboutOnBothSides()
        clone.png("star", white)
        clone.commitOnMain("feat: the star is red") { clone.png("star", red, it) }
        fetch(clone)

        Drafts.resolve(clone.pack, star, "main", mapOf("about" to "clone"))

        assertNull(draft(clone).conflict)
        assertEquals(clone.git(clone.clone, "rev-parse", "origin/main:resource-pack/$starPng"), blob(clone, clone.pngFile("star")))
        assertEquals(listOf("BIG_STAR", "Ours.", "keep"), listOf("constant", "about", "status").map { glyph(clone)[it] })
    }

    @Test
    fun aResolveThatTheGlyphRulesRefuseKeepsTheConflictAndEveryFile() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", white)
        clone.editBadge(clone.pack, "FE000", "about", "Ours.")
        clone.commitOnMain("docs: the star is red and described") {
            clone.png("star", red, it)
            clone.editBadge(it, "FE000", "about", "Theirs.")
            clone.editBadge(it, "FE000", "status", "lost")
        }
        fetch(clone)
        val before = blob(clone, clone.pngFile("star"))

        assertThrows<IllegalStateException> { Drafts.resolve(clone.pack, star, "main", mapOf("about" to "clone")) }

        assertEquals(listOf("about"), draft(clone).conflict?.fields?.map { it.name })
        assertEquals(before, blob(clone, clone.pngFile("star")))
        assertFalse(clone.pack.resolve("build/conflict/yours").exists())
    }

    @Test
    fun aResolveThatKeepsMainKeepsTheClonesVersionInTheBuild() {
        val clone = starOnBothSides()
        val mine = blob(clone, clone.pngFile("star"))

        val written = Drafts.resolve(clone.pack, star, "main", emptyMap())

        val yours = clone.pack.resolve("build/conflict/yours/$starPng")
        assertTrue(yours in written)
        assertEquals(mine, blob(clone, yours))
    }

    @Test
    fun aDiscardedDraftThatIsDrawnAgainIsAConflictAgain() {
        val clone = starOnBothSides()
        Drafts.resolve(clone.pack, star, "clone", emptyMap())

        Drafts.discard(clone.pack, star)
        clone.png("star", green)

        assertEquals(Drafts.Conflict(listOf(starPng), emptyList()), draft(clone).conflict)
    }

    @Test
    fun aPublishMakesMainItBranchedFromTheBaseOfItsDrafts() {
        val clone = starOnBothSides()
        Drafts.resolve(clone.pack, star, "clone", emptyMap())

        publish(clone).publish(listOf(star), "feat(resource-pack): the star is white")
        clone.png("star", green)

        assertEquals(clone.git(clone.clone, "rev-parse", "origin/main").trim(), Drafts.base(clone.pack, star))
        assertNull(draft(clone).conflict)
    }

    /** The glyphs of the star in the clone's `glyphs.json`. */
    private fun stars(clone: DraftClone) =
        NewTextures
            .fonts(OxfmtJson.read(clone.pack.resolve("glyphs.json")) as Map<*, *>)
            .flatMap { it["blocks"] as List<*> }
            .flatMap { (it as Map<*, *>)["glyphs"] as List<*> }
            .map { it as Map<*, *> }
            .filter { it["texture"] == star }

    @Test
    fun aCodePointThatMainMovedIsAFieldOfTheGlyphInAResolve() {
        val clone = DraftClone(root, "star@FE000")
        clone.editBadge(clone.pack, "FE000", "about", "Ours.")
        clone.commitOnMain("docs: the star moves and is described") {
            clone.editBadge(it, "FE000", "about", "Theirs.")
            clone.editBadge(it, "FE000", "code", "FE005")
        }
        fetch(clone)

        assertEquals(Drafts.Conflict(emptyList(), listOf(Drafts.Field("about", "\"Ours.\"", "\"Theirs.\""))), draft(clone).conflict)
        Drafts.resolve(clone.pack, star, null, mapOf("about" to "clone"))

        assertEquals(listOf("FE005" to "Ours."), stars(clone).map { it["code"] to it["about"] })
    }

    @Test
    fun aCodePointThatMainMovedIsAFieldOfTheGlyphInAPull() {
        val clone = DraftClone(root, "star@FE000", "moon@FE001")
        clone.png("moon", white)
        publish(clone).publish(listOf("nordtal:badges/moon.png"), "feat(resource-pack): the moon is white")
        clone.commitOnMain("feat: the moon is white and the star moves") {
            clone.png("moon", white, it)
            clone.editBadge(it, "FE000", "code", "FE005")
        }
        clone.editBadge(clone.pack, "FE000", "about", "Ours.")
        clone.prState.writeText("MERGED\n")

        publish(clone).sync()

        assertEquals(listOf("FE005" to "Ours."), stars(clone).map { it["code"] to it["about"] })
    }

    /** Moves the star on main into a new block of its own at FE100, in the pack [main]. */
    private fun moveStarToItsOwnBlock(main: File) {
        val file = main.resolve("glyphs.json")
        val root = OxfmtJson.read(file) as Map<*, *>
        val blocks = NewTextures.fonts(root).single()["blocks"] as MutableList<Any?>
        val glyphs = (blocks.single() as Map<*, *>)["glyphs"] as MutableList<Any?>
        val moved = glyphs.single { (it as Map<*, *>)["texture"] == star } as MutableMap<String, Any?>
        glyphs.remove(moved)
        moved["code"] = "FE100"
        blocks += OxfmtJson.read("""{ "range": "FE100-FE10F", "title": "Stars", "height": 8, "ascent": 6, "glyphs": [] }""")
        ((blocks.last() as Map<*, *>)["glyphs"] as MutableList<Any?>) += moved
        file.writeText(OxfmtJson.format(root))
    }

    /** The title of the block of each glyph of the star in the clone's `glyphs.json`, with its code and about text. */
    private fun starPlaces(clone: DraftClone) =
        NewTextures
            .fonts(OxfmtJson.read(clone.pack.resolve("glyphs.json")) as Map<*, *>)
            .flatMap { it["blocks"] as List<*> }
            .map { it as Map<*, *> }
            .flatMap { block ->
                (block["glyphs"] as List<*>).map { it as Map<*, *> }.filter { it["texture"] == star }.map {
                    listOf(block["title"], it["code"], it["about"])
                }
            }

    @Test
    fun aGlyphThatMainMovedIntoAnotherBlockStaysThereInAResolve() {
        val clone = DraftClone(root, "star@FE000")
        clone.editBadge(clone.pack, "FE000", "about", "Ours.")
        clone.commitOnMain("feat: the star moves and is described") {
            clone.editBadge(it, "FE000", "about", "Theirs.")
            moveStarToItsOwnBlock(it)
        }
        fetch(clone)

        Drafts.resolve(clone.pack, star, null, mapOf("about" to "clone"))

        assertEquals(listOf(listOf("Stars", "FE100", "Ours.")), starPlaces(clone))
    }

    @Test
    fun aGlyphThatMainMovedIntoAnotherBlockStaysThereInAPull() {
        val clone = DraftClone(root, "star@FE000", "moon@FE001")
        clone.png("moon", white)
        publish(clone).publish(listOf("nordtal:badges/moon.png"), "feat(resource-pack): the moon is white")
        clone.commitOnMain("feat: the moon is white and the star moves") {
            clone.png("moon", white, it)
            moveStarToItsOwnBlock(it)
        }
        clone.editBadge(clone.pack, "FE000", "about", "Ours.")
        clone.prState.writeText("MERGED\n")

        publish(clone).sync()

        assertEquals(listOf(listOf("Stars", "FE100", "Ours.")), starPlaces(clone))
        publish(clone).sync()
    }

    @Test
    fun theFieldsOfOneTextureInTwoFontsAreRowsOfTheirOwn() {
        val clone = DraftClone(root, "star@FE000")

        fun twoFonts(pack: File) {
            val file = pack.resolve("glyphs.json")
            val root = OxfmtJson.read(file) as Map<*, *>
            val fonts = root["fonts"] as MutableList<Any?>
            fonts += OxfmtJson.read(OxfmtJson.format(fonts.single() as Map<*, *>).replace("minecraft:default", "nordtal:board"))
            file.writeText(OxfmtJson.format(root))
        }
        twoFonts(clone.pack)
        clone.commitAll("feat: the star is in two fonts")
        clone.git(clone.clone, "push", "-q", "origin", "HEAD:main")

        fun about(
            pack: File,
            text: String,
        ) {
            val file = pack.resolve("glyphs.json")
            val root = OxfmtJson.read(file) as Map<*, *>
            NewTextures
                .fonts(root)
                .flatMap { it["blocks"] as List<*> }
                .flatMap { (it as Map<*, *>)["glyphs"] as List<*> }
                .forEach { (it as MutableMap<String, Any?>)["about"] = text }
            file.writeText(OxfmtJson.format(root))
        }
        about(clone.pack, "Ours.")
        clone.commitOnMain("docs: the star is described") { about(it, "Theirs.") }
        fetch(clone)

        assertEquals(listOf("minecraft:default about", "nordtal:board about"), draft(clone).conflict?.fields?.map { it.name })
    }

    @Test
    fun aResolvedConflictComesBackOnlyWhenMainChangesTheDraftAgain() {
        val clone = starOnBothSides()

        Drafts.resolve(clone.pack, star, "clone", emptyMap())
        assertNull(draft(clone).conflict)
        clone.commitOnMain("feat: the star is green") { clone.png("star", green, it) }
        fetch(clone)

        assertEquals(Drafts.Conflict(listOf(starPng), emptyList()), draft(clone).conflict)
    }

    @Test
    fun aResolveThatLacksAChoiceIsRefused() {
        val clone = aboutOnBothSides()
        clone.png("star", white)
        clone.commitOnMain("feat: the star is red") { clone.png("star", red, it) }
        fetch(clone)

        assertEquals("keep", assertThrows<FieldRefusal> { Drafts.resolve(clone.pack, star, null, mapOf("about" to "main")) }.field)
        assertEquals("fields", assertThrows<FieldRefusal> { Drafts.resolve(clone.pack, star, "main", emptyMap()) }.field)
    }

    @Test
    fun aPullMergesTheFieldsAndKeepsAConflictForTheDesigner() {
        val clone = DraftClone(root, "star@FE000", "moon@FE001")
        clone.png("moon", white)
        publish(clone).publish(listOf("nordtal:badges/moon.png"), "feat(resource-pack): the moon is white")
        clone.commitOnMain("feat: the moon is white, the star red and described") {
            clone.png("moon", white, it)
            clone.png("star", red, it)
            clone.editBadge(it, "FE000", "about", "Theirs.")
            clone.editBadge(it, "FE000", "status", "keep")
        }
        clone.png("star", green)
        clone.editBadge(clone.pack, "FE000", "constant", "BIG_STAR")
        clone.editBadge(clone.pack, "FE000", "about", "Ours.")
        clone.prState.writeText("MERGED\n")

        publish(clone).sync()

        assertEquals(clone.git(clone.clone, "rev-parse", "origin/main"), clone.git(clone.clone, "rev-parse", "HEAD"))
        assertEquals(
            Drafts.Conflict(listOf(starPng), listOf(Drafts.Field("about", "\"Ours.\"", "\"Theirs.\""))),
            draft(clone).conflict,
        )
        assertEquals(listOf("BIG_STAR", "Ours.", "keep"), listOf("constant", "about", "status").map { glyph(clone)[it] })
    }

    @Test
    fun theTaskReadsTheChoiceOfEachFieldAsAJsonObject() {
        val clone = aboutOnBothSides()

        val refusal = assertThrows<GradleException> { task(clone, "resolve", "draft" to star, "fields" to "about=clone").create() }
        task(clone, "resolve", "draft" to star, "fields" to """{ "about": "main" }""").create()

        assertEquals("-Pfields=about=clone is no JSON object.", refusal.message)
        assertNull(draft(clone).conflict)
        assertEquals("Theirs.", glyph(clone)["about"])
    }

    /** The task `edit` of [kind] in the clone, with [options]. */
    private fun task(
        clone: DraftClone,
        kind: String,
        vararg options: Pair<String, String>,
    ): EditPackSources {
        val project = ProjectBuilder.builder().withProjectDir(clone.clone).build()
        val task = project.tasks.register("edit", EditPackSources::class.java).get()
        task.kind.set(kind)
        task.options.set(options.toMap())
        task.pack.set(clone.pack)
        task.repository.set(clone.clone)
        return task
    }

    @Test
    fun theConflictReadRemovesWhatAnEarlierReadLeftOfTheDraft() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", white)
        clone.commitOnMain("feat: the star goes") { clone.pngFile("star", it).delete() }
        fetch(clone)
        val stale = clone.pack.resolve("build/conflict/$starPng")
        stale.parentFile.mkdirs()
        stale.writeText("an earlier read")

        assertEquals(emptyList<File>(), Drafts.readConflict(clone.pack, star))

        assertFalse(stale.exists())
    }

    @Test
    fun theConflictReadWritesMainsVersionOfEachFile() {
        val clone = starOnBothSides()

        val written = Drafts.readConflict(clone.pack, star)

        assertEquals(listOf(clone.pack.resolve("build/conflict/$starPng")), written)
        assertEquals(clone.git(clone.clone, "rev-parse", "origin/main:resource-pack/$starPng"), blob(clone, written.single()))
    }
}
