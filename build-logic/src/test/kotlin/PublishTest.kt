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

    /** The `glyphs.json` and the PNGs of the pack in each work tree that the check ran on. */
    private val checked = mutableListOf<Pair<String, List<String>>>()

    private fun publish(
        clone: DraftClone,
        check: (File) -> Unit = {},
    ) = Publish(clone.clone, clone.pack, clone.gh.absolutePath) { tree ->
        val pack = tree.resolve("resource-pack")
        checked += pack.resolve("glyphs.json").readText() to
            pack
                .walk()
                .filter { it.extension == "png" }
                .map { it.name }
                .sorted()
                .toList()
        check(tree)
    }

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
    fun theCheckRunsOnTheWorkTreeWithOnlyTheChosenDraftsAfterTheCodePointsAreAssigned() {
        val clone = DraftClone(root, "star@FE000")
        clone.writeGlyphs("star@FE000", "comet@FE001", "moon@FE002")
        clone.png("comet", 0xFFFFFFFF.toInt())
        clone.png("moon", 0xFFFFFFFF.toInt())
        clone.commitOnMain("feat: sun") { pack ->
            pack.resolve("glyphs.json").writeText(OxfmtJson.format(OxfmtJson.read(clone.glyphs("star@FE000", "sun@FE001"))))
        }

        publish(clone).publish(listOf("nordtal:badges/comet.png"), "feat(resource-pack): a comet joins the badges")

        val (glyphs, pngs) = checked.single()
        assertTrue(""""code": "FE002", "constant": "COMET"""" in glyphs.replace(Regex("\\s+"), " "), glyphs)
        assertFalse("MOON" in glyphs)
        assertEquals(listOf("comet.png", "star.png"), pngs)
    }

    @Test
    fun aCheckThatFailsSendsNothingAndNamesItsReason() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())

        val failure =
            assertThrows<GradleException> {
                publish(clone) { throw GradleException("the star is too bright") }
                    .publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
            }

        assertEquals("the star is too bright", failure.message)
        assertEquals("main\n", clone.git(clone.origin, "branch", "--format=%(refname:short)"))
        assertFalse(clone.ghLog.exists() && clone.ghLog.readText().contains("pr create"))
        assertEquals(1, clone.git(clone.clone, "worktree", "list", "--porcelain").lines().count { it.startsWith("worktree ") })
    }

    @Test
    fun aPullRequestThatCannotBeOpenedTakesItsBranchBack() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())
        clone.prCreateFails.writeText("")

        assertThrows<GradleException> {
            publish(
                clone,
            ).publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
        }

        assertEquals("main\n", clone.git(clone.origin, "branch", "--format=%(refname:short)"))
        assertEquals(emptyList<Drafts.Review>(), Drafts.reviews(clone.pack))
        assertEquals(listOf("nordtal:badges/star.png" to null), ids(clone))
    }

    @Test
    fun aDeletedTextureIsPublishedAsItsDeletion() {
        val clone = DraftClone(root, "star@FE000", "moon@FE001")
        clone.pngFile("moon").delete()
        clone.writeGlyphs("star@FE000")

        publish(clone).publish(listOf("nordtal:badges/moon.png"), "feat(resource-pack): the moon leaves the badges")

        val branch = "designer/the-moon-leaves-the-badges"
        assertEquals(
            "",
            clone
                .git(
                    clone.origin,
                    "ls-tree",
                    "--name-only",
                    "$branch:resource-pack/src/assets/nordtal/textures/badges",
                ).replace("star.png\n", ""),
        )
        assertEquals(OxfmtJson.format(OxfmtJson.read(clone.glyphs("star@FE000"))), clone.onOrigin(branch, "resource-pack/glyphs.json"))
        assertEquals(emptyList<Pair<String, Int?>>(), ids(clone))
    }

    @Test
    fun aLanguageFileIsPublishedAsADraftOfItsOwn() {
        val clone = DraftClone(root, "star@FE000")
        val lang = "src/assets/nordtal/lang/en_us.json"
        clone.pack
            .resolve(lang)
            .apply { parentFile.mkdirs() }
            .writeText("{ \"badge.star\": \"Star\" }\n")

        publish(clone).publish(listOf(lang), "feat(resource-pack): the badges have english names")

        assertEquals("{ \"badge.star\": \"Star\" }\n", clone.onOrigin("designer/the-badges-have-english-names", "resource-pack/$lang"))
        assertEquals(emptyList<Pair<String, Int?>>(), ids(clone))
    }

    @Test
    fun aCommitOnThePullRequestBeforeTheMergeSurvivesThePull() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())
        publish(clone).publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
        val branch = "designer/the-star-is-white"
        clone.commitOn(branch, "fix: the star is red") { pack ->
            val image = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until 8) for (y in 0 until 8) image.setRGB(x, y, 0xFFFF0000.toInt())
            javax.imageio.ImageIO.write(image, "png", pack.resolve("src/assets/nordtal/textures/badges/star.png"))
        }
        clone.git(clone.origin, "update-ref", "refs/heads/main", branch)
        clone.prState.writeText("MERGED\n")

        assertEquals(listOf("merged 42"), publish(clone).sync())

        assertEquals(clone.git(clone.origin, "rev-parse", "main:$starPng"), clone.git(clone.clone, "hash-object", starPng))
        assertEquals(emptyList<Pair<String, Int?>>(), ids(clone))
    }

    @Test
    fun aPullThatFailsAfterTheRestoreStillPutsEveryDraftBack() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())
        publish(clone).publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
        clone.writeGlyphs("star@FE000", "comet@FE001")
        clone.png("comet", 0xFFFFFFFF.toInt())
        clone.commitOnMain("feat: sun") { pack ->
            pack.resolve("glyphs.json").writeText(OxfmtJson.format(OxfmtJson.read(clone.glyphs("star@FE000", "sun@FE001=COMET"))))
        }
        clone.prState.writeText("MERGED\n")
        val head = clone.git(clone.clone, "rev-parse", "HEAD")

        assertThrows<IllegalStateException> { publish(clone).sync() }

        assertEquals(head, clone.git(clone.clone, "rev-parse", "HEAD"))
        assertTrue(clone.pngFile("comet").isFile)
        assertEquals(listOf("nordtal:badges/comet.png" to null), ids(clone))
        assertEquals("", clone.git(clone.clone, "stash", "list"))
        assertEquals(listOf(42), Drafts.reviews(clone.pack).map { it.number })
    }

    /** Leaves the clone's changes in the stash of a pull that stopped halfway. */
    private fun leaveAPullHalfway(clone: DraftClone) {
        clone.git(
            clone.clone,
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@t",
            "stash",
            "push",
            "-q",
            "--include-untracked",
            "-m",
            "nordtal-designer-pull",
        )
    }

    @Test
    fun aPullThatStoppedHalfwayIsPutBackByTheNextSync() {
        val clone = DraftClone(root, "star@FE000")
        clone.clone.resolve("mine.txt").writeText("the owner's own stash\n")
        clone.git(clone.clone, "stash", "push", "-q", "--include-untracked", "-m", "mine")
        clone.png("star", 0xFFFFFFFF.toInt())
        leaveAPullHalfway(clone)

        assertEquals(listOf("restored"), publish(clone).sync())

        assertEquals(listOf("nordtal:badges/star.png" to null), ids(clone))
        assertEquals("On main: mine\n", clone.git(clone.clone, "stash", "list", "--format=%gs"))
    }

    @Test
    fun anEditMadeAfterAPullStoppedHalfwaySurvivesThePutBack() {
        val clone = DraftClone(root, "star@FE000", "moon@FE001")
        clone.png("star", 0xFFFFFFFF.toInt())
        leaveAPullHalfway(clone)
        clone.png("moon", 0xFF00FF00.toInt())
        val notes = clone.pack.resolve("src/assets/nordtal/lang/en_us.json")
        notes.parentFile.mkdirs()
        notes.writeText("{}\n")

        assertEquals(listOf("restored"), publish(clone).sync())

        assertEquals(
            listOf("nordtal:badges/moon.png", "nordtal:badges/star.png", "src/assets/nordtal/lang/en_us.json"),
            ids(clone).map { it.first },
        )
        assertEquals("", clone.git(clone.clone, "stash", "list"))
    }

    @Test
    fun anEditThatClashesWithAPullStoppedHalfwayKeepsBothStashesAndIsRefused() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())
        leaveAPullHalfway(clone)
        clone.png("star", 0xFF00FF00.toInt())

        val refusal = assertThrows<GradleException> { publish(clone).sync() }

        assertTrue("nordtal-designer-pull" in refusal.message!! && "nordtal-designer-edits" in refusal.message!!, refusal.message)
        assertEquals(
            "On main: nordtal-designer-edits\nOn main: nordtal-designer-pull\n",
            clone.git(clone.clone, "stash", "list", "--format=%gs"),
        )
        assertEquals("", clone.git(clone.clone, "status", "--porcelain"))
        assertThrows<GradleException> { publish(clone).sync() }
    }

    @Test
    fun aMergeWithNoLocalChangesLeavesTheOwnersStashAlone() {
        val clone = DraftClone(root, "star@FE000")
        clone.png("star", 0xFFFFFFFF.toInt())
        publish(clone).publish(listOf("nordtal:badges/star.png"), "feat(resource-pack): the star is white")
        clone.pngFile("star").delete()
        clone.git(clone.clone, "checkout", "-q", "--", ".")
        clone.clone.resolve("mine.txt").writeText("the owner's own stash\n")
        clone.git(clone.clone, "stash", "push", "-q", "--include-untracked", "-m", "mine")
        clone.git(clone.origin, "update-ref", "refs/heads/main", "designer/the-star-is-white")
        clone.prState.writeText("MERGED\n")

        assertEquals(listOf("merged 42"), publish(clone).sync())

        assertEquals(clone.git(clone.origin, "rev-parse", "main"), clone.git(clone.clone, "rev-parse", "HEAD"))
        assertEquals("On main: mine\n", clone.git(clone.clone, "stash", "list", "--format=%gs"))
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
