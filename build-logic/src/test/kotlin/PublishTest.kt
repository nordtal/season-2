package eu.nordtal.season.build

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PublishTest {
    @TempDir
    lateinit var root: File

    private val starPng = "resource-pack/src/assets/nordtal/textures/badges/star.png"

    private fun publish(clone: DraftClone) = Publish(clone.clone, clone.pack, clone.gh.absolutePath)

    private fun ids(clone: DraftClone) = Drafts.list(clone.pack).map { it.id to it.review }

    @Test
    fun aPublishPushesOnlyTheChosenDraftsOntoMainAndOpensOnePullRequest() {
        val clone = DraftClone(root, "star@FE000", "moon@FE001")
        clone.png("star", 0xFFFFFFFF.toInt())
        clone.writeGlyphs("star@FE000", "moon@FE001=HALF_MOON")

        val url = publish(clone).publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")

        assertEquals("https://github.com/nordtal/season-2/pull/42", url)
        val branch = "designer/the-star-is-white"
        assertEquals(clone.git(clone.clone, "hash-object", starPng), clone.git(clone.origin, "rev-parse", "$branch:$starPng"))
        assertEquals(clone.onOrigin("main", "resource-pack/glyphs.json"), clone.onOrigin(branch, "resource-pack/glyphs.json"))
        assertEquals(
            "feat(resource-pack): the star is white\nDee Drawer <7+drawer@users.noreply.github.com>\nDee Drawer\n",
            clone.git(clone.origin, "log", "-1", "--format=%s%n%an <%ae>%n%cn", branch),
        )
        assertEquals(clone.git(clone.origin, "rev-parse", "main"), clone.git(clone.origin, "rev-parse", "$branch~1"))
        val created = clone.ghLog.readLines().single { it.startsWith("pr create") }
        assertTrue(
            created.startsWith("pr create --base main --head $branch --title feat(resource-pack): the star is white --body - star"),
            created,
        )
        assertEquals(listOf("nordtal:badges/moon.png" to null), ids(clone))
    }

    @Test
    fun aFurtherPublishOfAChangeInReviewAddsACommitToItsPullRequest() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())
        publish(clone).publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
        clone.png("star", 0xFF00FF00.toInt())

        assertEquals(listOf("nordtal:badges/star.png" to 42), ids(clone))
        val url = publish(clone).publish(listOf("nordtal:badges/star.png"), "fix(resource-pack): the star is green")

        assertEquals("https://github.com/nordtal/season-2/pull/42", url)
        assertEquals(
            "fix(resource-pack): the star is green\nfeat(resource-pack): the star is white\nfeat: start\n",
            clone.git(clone.origin, "log", "--format=%s", "designer/the-star-is-white"),
        )
        assertEquals(1, clone.ghLog.readLines().count { it.startsWith("pr create") })
        assertEquals(emptyList<Pair<String, Int?>>(), ids(clone))
    }

    @Test
    fun aRenamedBlockKeepsItsRangeAndIsPublishedUnderItsNewTitle() {
        val clone = DraftClone(root, "star@FE000")
        clone.pack
            .resolve(
                "glyphs.json",
            ).writeText(OxfmtJson.format(OxfmtJson.read(clone.glyphs("star@FE000").replace("\"Badges\"", "\"Stars\""))))

        assertEquals(listOf("block minecraft:default FE000-FE00F" to null), ids(clone))
        publish(clone).publish(listOf("block minecraft:default FE000-FE00F"), "feat(resource-pack): the badges are stars")

        val published = clone.onOrigin("designer/the-badges-are-stars", "resource-pack/glyphs.json")
        assertTrue(published.contains("\"range\": \"FE000-FE00F\",\n          \"title\": \"Stars\""), published)
        assertTrue(
            clone.pack
                .resolve("glyphs.json")
                .readText()
                .contains("\"range\": \"FE000-FE00F\""),
        )
        assertEquals(emptyList<Pair<String, Int?>>(), ids(clone))
    }

    @Test
    fun aPublishGivesANewGlyphTheCodePointThatMainLeftFreeOnTheBranchAndInTheClone() {
        val clone = DraftClone(root, "star@FE000")
        clone.writeGlyphs("star@FE000", "comet@FE001")
        clone.png("comet", 0xFFFFFFFF.toInt())
        clone.commitOnMain("feat: moon") { pack ->
            pack.resolve("glyphs.json").writeText(OxfmtJson.format(OxfmtJson.read(clone.glyphs("star@FE000", "moon@FE001"))))
        }

        publish(clone).publish(listOf("nordtal:badges/comet.png"), "feat(resource-pack): a comet joins the badges")

        val codes = { text: String -> Regex(""""code": "(\w+)"""").findAll(text).map { it.groupValues[1] }.toList() }
        assertEquals(
            listOf("FE000", "FE001", "FE002"),
            codes(clone.onOrigin("designer/a-comet-joins-the-badges", "resource-pack/glyphs.json")),
        )
        assertEquals(listOf("FE000", "FE002"), codes(clone.pack.resolve("glyphs.json").readText()))
    }

    @Test
    fun aTitleThatTheCommitHookRefusesSendsNothing() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())

        val refusal = assertThrows<GradleException> { publish(clone).publish(listOf("nordtal:badges/star.png"), "white star") }

        assertTrue("is not type(scope): description" in refusal.message!!, refusal.message)
        assertEquals("main\n", clone.git(clone.origin, "branch", "--format=%(refname:short)"))
        assertFalse(clone.ghLog.exists() && clone.ghLog.readText().contains("pr create"))
        assertEquals(listOf("nordtal:badges/star.png" to null), ids(clone))
        assertEquals(
            "",
            clone
                .git(clone.clone, "worktree", "list", "--porcelain")
                .lines()
                .drop(4)
                .joinToString(""),
        )
    }

    @Test
    fun aMergedPullRequestPullsMainAndKeepsEveryOtherDraft() {
        val clone = DraftClone(root, "star@FE000", "moon@FE001")
        clone.png("star", 0xFFFFFFFF.toInt())
        clone.writeGlyphs("star@FE000", "moon@FE001=HALF_MOON", "comet@FE002")
        clone.png("comet", 0xFFFFFFFF.toInt())
        publish(clone).publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
        clone.git(clone.origin, "update-ref", "refs/heads/main", "designer/the-star-is-white")
        clone.prState.writeText("MERGED\n")

        assertEquals(listOf("merged 42"), publish(clone).sync())

        assertEquals(clone.git(clone.origin, "rev-parse", "main"), clone.git(clone.clone, "rev-parse", "HEAD"))
        assertEquals(listOf("nordtal:badges/comet.png" to null, "nordtal:badges/moon.png" to null), ids(clone))
        assertEquals("", clone.git(clone.clone, "stash", "list"))
        assertEquals(emptyList<String>(), publish(clone).sync())
    }

    @Test
    fun aPullThatFailsPutsEveryDraftBack() {
        val clone = DraftClone(root, "star@FE000")
        clone.clone.resolve("notes.txt").writeText("only here\n")
        clone.commitAll("docs: notes")
        clone.png("star", 0xFFFFFFFF.toInt())
        clone.writeGlyphs("star@FE000", "comet@FE001")
        clone.png("comet", 0xFFFFFFFF.toInt())
        publish(clone).publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
        clone.git(clone.origin, "update-ref", "refs/heads/main", "designer/the-star-is-white")
        clone.prState.writeText("MERGED\n")
        val before = ids(clone)

        assertThrows<GradleException> { publish(clone).sync() }

        assertEquals(listOf("nordtal:badges/comet.png" to null), before)
        assertEquals(before, ids(clone))
        assertEquals("", clone.git(clone.clone, "stash", "list"))
        assertEquals(listOf(42), Drafts.reviews(clone.pack).map { it.number })
    }

    @Test
    fun aPullRequestClosedWithoutAMergeTurnsItsChangesBackIntoDrafts() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())
        publish(clone).publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
        clone.prState.writeText("CLOSED\n")

        assertEquals(listOf("closed 42"), publish(clone).sync())

        assertEquals(listOf("nordtal:badges/star.png" to null), ids(clone))
    }
}
