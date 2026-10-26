package eu.nordtal.season.build

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files

/**
 * Publishes drafts of the pack at [pack], in the clone [repository], as pull requests against `main`, and follows those
 * pull requests until they are merged or closed. [gh] is the GitHub CLI, signed in to the account that commits.
 */
class Publish(
    private val repository: File,
    private val pack: File,
    private val gh: String = "gh",
) {
    /**
     * Commits the drafts [ids] with the message [title] on a branch of `origin/main` that holds only them, pushes it and
     * opens its pull request, or adds the commit to the pull request that holds them already. New glyphs get their final
     * code points against `origin/main` first, in the clone too. Returns the pull request's address.
     */
    fun publish(
        ids: List<String>,
        title: String,
    ): String {
        sync()
        git(repository, listOf("fetch", "-q", "origin"))
        GlyphEdits.assignCodePoints(pack, git(pack, listOf("show", "origin/main:./glyphs.json")))
        val drafts = Drafts.list(pack).filter { it.id in ids }
        require(drafts.isNotEmpty() && drafts.size == ids.toSet().size) {
            "${ids.toSet() - drafts.map { it.id }.toSet()} are no drafts."
        }
        val reviews = Drafts.reviews(pack)
        val held = drafts.mapNotNull { it.review }.distinct()
        require(held.size <= 1) { "The chosen drafts are in the pull requests ${held.joinToString()}; publish each one's on its own." }
        val review = held.singleOrNull()?.let { number -> reviews.first { it.number == number } }
        val branch = review?.branch ?: branchFor(title)
        val (name, email) = author()
        val tree = Files.createTempDirectory("publish").toFile()
        val work = tree.resolve("tree")
        git(repository, listOf("worktree", "add", "-q", "--detach", work.absolutePath, "origin/${review?.branch ?: "main"}"))
        try {
            val path = pack.relativeTo(repository).invariantSeparatorsPath
            Drafts.copy(pack, work.resolve(path), drafts)
            git(work, listOf("add", "-A", "--", path))
            git(work, listOf("-c", "user.name=$name", "-c", "user.email=$email", "commit", "-q", "-m", title))
            git(work, listOf("push", "-q", "origin", "HEAD:refs/heads/$branch"))
        } finally {
            git(repository, listOf("worktree", "remove", "--force", work.absolutePath))
            tree.deleteRecursively()
        }
        val url =
            review?.url
                ?: run(
                    gh,
                    repository,
                    listOf(
                        "pr",
                        "create",
                        "--base",
                        "main",
                        "--head",
                        branch,
                        "--title",
                        title,
                        "--body",
                        drafts
                            .joinToString(
                                "\n",
                            ) {
                                "- ${it.label}"
                            },
                    ),
                ).lines()
                    .last { it.startsWith("https://") }
        val number = url.substringAfterLast('/').toInt()
        Drafts.writeReviews(
            pack,
            reviews.filter { it.number != number } + Drafts.Review(number, url, branch, (review?.drafts.orEmpty() + ids).distinct()),
        )
        return url
    }

    /**
     * Asks GitHub for the state of every pull request a publish opened. After a merge the clone pulls `main` and keeps
     * its other drafts; a pull request closed without a merge turns its changes back into drafts. Returns one line per
     * pull request that ended, such as `merged 42`.
     */
    fun sync(): List<String> {
        val reviews = Drafts.reviews(pack)
        val ended = mutableListOf<String>()
        val open =
            reviews.filter { review ->
                when (run(gh, repository, listOf("pr", "view", review.number.toString(), "--json", "state", "--jq", ".state")).trim()) {
                    "MERGED" -> false.also { ended += "merged ${review.number}" }
                    "CLOSED" -> false.also { ended += "closed ${review.number}" }
                    else -> true
                }
            }
        if (ended.any { it.startsWith("merged") }) pull()
        if (open.size != reviews.size) Drafts.writeReviews(pack, open)
        return ended
    }

    /**
     * Moves the clone to `origin/main` and puts every local change back on top of it. A file both changed keeps the
     * clone's version; `glyphs.json` takes the clone's entries onto main's. A pull that fails leaves the clone as it was.
     */
    private fun pull() {
        git(repository, listOf("fetch", "-q", "origin"))
        val head = git(repository, listOf("rev-parse", "HEAD")).trim()
        val before = stash()
        val (name, email) = author()
        git(repository, listOf("-c", "user.name=$name", "-c", "user.email=$email", "stash", "push", "-q", "--include-untracked"))
        val stash = stash()?.takeIf { it != before }
        if (stash == null) {
            git(repository, listOf("merge", "-q", "--ff-only", "origin/main"))
            return
        }
        val untracked = runCatching { paths(listOf("ls-tree", "-r", "-z", "--name-only", "$stash^3")) }.getOrDefault(emptyList())
        try {
            git(repository, listOf("merge", "-q", "--ff-only", "origin/main"))
            val glyphs = pack.resolve("glyphs.json").relativeTo(repository).invariantSeparatorsPath
            val changed = paths(listOf("diff", "--name-only", "-z", "$stash^1", stash))
            val kept = paths(listOf("ls-tree", "-r", "-z", "--name-only", stash)).toSet()
            val (restored, deleted) = (changed - glyphs).partition { it in kept }
            if (restored.isNotEmpty()) git(repository, listOf("restore", "--source=$stash", "--worktree", "--") + restored)
            if (untracked.isNotEmpty()) git(repository, listOf("restore", "--source=$stash^3", "--worktree", "--") + untracked)
            AtomicFiles.write(deleted.map { repository.resolve(it) }.filter { it.exists() }.associateWith { null })
            if (glyphs in changed) {
                val mine = Drafts.glyphs(pack, stash)
                val ids = Drafts.changedIds(Drafts.glyphs(pack, "$stash^1"), mine)
                NewTextures.edit(pack, null, 0, 0) { Drafts.apply(it, mine, ids) }
            }
        } catch (failure: Exception) {
            // The clone goes back to where it was, with every draft, so the next sync tries again.
            git(repository, listOf("reset", "-q", "--hard", head))
            AtomicFiles.write(untracked.map { repository.resolve(it) }.filter { it.exists() }.associateWith { null })
            git(repository, listOf("stash", "pop", "-q"))
            throw failure
        }
        git(repository, listOf("stash", "drop", "-q"))
    }

    private fun stash(): String? = runCatching { git(repository, listOf("rev-parse", "-q", "--verify", "refs/stash")).trim() }.getOrNull()

    private fun paths(arguments: List<String>) = git(repository, arguments).split('\u0000').filter { it.isNotBlank() }

    /** The name and address of the signed-in GitHub account, its no-reply address where it shows none. */
    private fun author(): Pair<String, String> {
        val user = JsonSlurper().parseText(run(gh, repository, listOf("api", "user"))) as Map<*, *>
        val login = user["login"] as String
        return ((user["name"] as String?)?.takeIf { it.isNotBlank() } ?: login) to
            ((user["email"] as String?)?.takeIf { it.isNotBlank() } ?: "${user["id"]}+$login@users.noreply.github.com")
    }

    /** `designer/` and the description of [title] in lowercase words, with a number where origin has that branch already. */
    private fun branchFor(title: String): String {
        val slug =
            title
                .substringAfter(": ")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .take(50)
                .trim('-')
        if (slug.isEmpty()) throw GradleException("\"$title\" has no description to name a branch by.")
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) "designer/$slug" else "designer/$slug-$it" }
            .first { runCatching { git(repository, listOf("rev-parse", "-q", "--verify", "refs/remotes/origin/$it")) }.isFailure }
    }
}
