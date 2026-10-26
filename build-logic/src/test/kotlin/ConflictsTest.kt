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
    fun theConflictReadWritesMainsVersionOfEachFile() {
        val clone = starOnBothSides()

        val written = Drafts.readConflict(clone.pack, star)

        assertEquals(listOf(clone.pack.resolve("build/conflict/$starPng")), written)
        assertEquals(clone.git(clone.clone, "rev-parse", "origin/main:resource-pack/$starPng"), blob(clone, written.single()))
    }
}
