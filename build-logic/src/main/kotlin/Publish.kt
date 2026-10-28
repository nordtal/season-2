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
    private val check: (File) -> Unit = ::checkPack,
) {
    /**
     * Commits the drafts [ids] with the message [title] on a branch of `origin/main` that holds only them, after their
     * final code points and the check, pushes it and opens its pull request, or adds the commit to the pull request that
     * holds them already. Returns the pull request's address.
     */
    fun publish(
        ids: List<String>,
        title: String,
    ): String {
        sync()
        git(repository, listOf("fetch", "-q", "origin"))
        val main = git(repository, listOf("rev-parse", "origin/main")).trim()
        GlyphEdits.assignCodePoints(pack, git(pack, listOf("show", "$main:./glyphs.json")))
        val drafts = Drafts.list(pack).filter { it.id in ids }
        require(drafts.isNotEmpty() && drafts.size == ids.toSet().size) {
            "${ids.toSet() - drafts.map { it.id }.toSet()} are no drafts."
        }
        val conflicts = drafts.filter { it.conflict != null }.map { it.label }
        require(conflicts.isEmpty()) { "Main changed ${conflicts.joinToString()} too; resolve each conflict before the publish." }
        val reviews = Drafts.reviews(pack)
        val held = drafts.mapNotNull { it.review }.distinct()
        require(held.size <= 1) { "The chosen drafts are in the pull requests ${held.joinToString()}; publish each one's on its own." }
        val review = held.singleOrNull()?.let { number -> reviews.first { it.number == number } }
        val branch = review?.branch ?: branchFor(title)
        val (name, email) = author()
        val tree = Files.createTempDirectory("publish").toFile()
        val work = tree.resolve("tree")
        val commit =
            try {
                git(repository, listOf("worktree", "add", "-q", "--detach", work.absolutePath, "origin/${review?.branch ?: "main"}"))
                val path = pack.relativeTo(repository).invariantSeparatorsPath
                Drafts.copy(pack, work.resolve(path), drafts, merge = review == null)
                check(work)
                git(work, listOf("add", "-A", "--", path))
                git(work, listOf("-c", "user.name=$name", "-c", "user.email=$email", "commit", "-q", "-m", title))
                git(work, listOf("push", "-q", "origin", "HEAD:refs/heads/$branch"))
                git(work, listOf("rev-parse", "HEAD")).trim()
            } finally {
                // A work tree that is left over must not hide why the publish failed.
                runCatching { git(repository, listOf("worktree", "remove", "--force", work.absolutePath)) }
                tree.deleteRecursively()
                runCatching { git(repository, listOf("worktree", "prune")) }
            }
        val url = review?.url ?: open(branch, title, drafts)
        val number = url.substringAfterLast('/').toInt()
        val heldIds = (review?.drafts.orEmpty() + ids).distinct()
        Drafts.writeReviews(pack, reviews.filter { it.number != number } + Drafts.Review(number, url, branch, heldIds, commit))
        Drafts.rebase(pack, ids.toSet(), main)
        return url
    }

    /** Opens the pull request of [branch] and returns its address, or deletes the branch on origin when that fails. */
    private fun open(
        branch: String,
        title: String,
        drafts: List<Drafts.Draft>,
    ): String {
        val body = drafts.joinToString("\n") { "- ${it.label}" }
        try {
            return run(gh, repository, listOf("pr", "create", "--base", "main", "--head", branch, "--title", title, "--body", body))
                .lines()
                .last { it.startsWith("https://") }
        } catch (failure: Exception) {
            runCatching { git(repository, listOf("push", "-q", "origin", "--delete", branch)) }
            throw failure
        }
    }

    /**
     * Fetches main and asks GitHub for the state, review and checks of every pull request a publish opened, after it puts
     * back a pull that stopped halfway. A merge pulls `main`; a close turns the pull request's changes back into drafts.
     * Returns a line per change: `merged 42`, `closed 42`, `reviewed 42`, `unanswered 42` where GitHub did not answer,
     * `moved` for main and `restored` after a put back.
     */
    fun sync(): List<String> {
        val ended = mutableListOf<String>()
        if (resume()) ended += "restored"
        val before = runCatching { git(repository, listOf("rev-parse", "-q", "--verify", "origin/main")) }.getOrNull()
        try {
            git(repository, listOf("fetch", "-q", "origin"))
        } catch (failure: GradleException) {
            throw GradleException("origin could not be fetched: ${failure.message}", failure)
        }
        if (git(repository, listOf("rev-parse", "origin/main")) != before) ended += "moved"
        val reviews = Drafts.reviews(pack)
        val merged = mutableListOf<Drafts.Review>()
        val open =
            reviews.mapNotNull { review ->
                val answer =
                    runCatching {
                        val pr = view(review.number)
                        pr["state"] to if (pr["state"] == "MERGED" || pr["state"] == "CLOSED") review else reviewed(review, pr)
                    }.getOrNull()
                when (answer?.first) {
                    null -> review.also { ended += "unanswered ${review.number}" }
                    "MERGED" -> null.also { ended += "merged ${review.number}" }.also { merged += review }
                    "CLOSED" -> null.also { ended += "closed ${review.number}" }
                    else -> answer.second.also { if (it != review) ended += "reviewed ${review.number}" }
                }
            }
        if (merged.isNotEmpty()) pull(merged)
        if (open != reviews) Drafts.writeReviews(pack, open)
        return ended
    }

    /** The state, review decision, checks and reviews of the pull request [number]. */
    private fun view(number: Int): Map<*, *> =
        JsonSlurper().parseText(
            run(gh, repository, listOf("pr", "view", number.toString(), "--json", "state,reviewDecision,statusCheckRollup,reviews")),
        ) as Map<*, *>

    /** [review] with the decision, the checks and the comments of [pr], its pull request as GitHub shows it. */
    private fun reviewed(
        review: Drafts.Review,
        pr: Map<*, *>,
    ): Drafts.Review {
        val checks = (pr["statusCheckRollup"] as List<*>?).orEmpty().map { it as Map<*, *> }
        val outcomes = checks.map { (it["conclusion"] ?: it["state"]).toString() }
        val lines =
            JsonSlurper().parseText(
                run(gh, repository, listOf("api", "repos/{owner}/{repo}/pulls/${review.number}/comments?per_page=100")),
            ) as List<*>
        val comments =
            (pr["reviews"] as List<*>?).orEmpty().map { it as Map<*, *> }.filter { !(it["body"] as String?).isNullOrBlank() }.map {
                Drafts.Comment(
                    ((it["author"] as Map<*, *>?)?.get("login") ?: "").toString(),
                    shortened(it["body"] as String),
                    null,
                    review.url,
                )
            } +
                lines.map { it as Map<*, *> }.map {
                    Drafts.Comment(
                        ((it["user"] as Map<*, *>?)?.get("login") ?: "").toString(),
                        shortened(it["body"] as String? ?: ""),
                        it["path"] as String?,
                        it["html_url"] as String? ?: review.url,
                    )
                }
        return review.copy(
            decision = (pr["reviewDecision"] as String?)?.takeIf { it.isNotEmpty() },
            checks =
                when {
                    checks.isEmpty() -> null
                    outcomes.any { it in FAILED } -> "failure"
                    checks.any { (it["status"] ?: "COMPLETED") != "COMPLETED" } || outcomes.any { it in PENDING } -> "pending"
                    else -> "success"
                },
            comments = comments,
        )
    }

    /** The text of a comment's [body] without its markup, hidden parts and folded details, cut to a few lines. */
    private fun shortened(body: String): String {
        val text =
            body
                .replace(Regex("(?s)<!--.*?-->|<details>.*?</details>"), " ")
                .replace(Regex("<[^>]+>|[*`#]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        if (text.length <= COMMENT) return text
        val end = if (text[COMMENT - 2].isHighSurrogate()) COMMENT - 2 else COMMENT - 1
        return text.take(end).trimEnd() + "…"
    }

    /** A stash entry: [ref] such as `stash@{0}` for pop and drop, [commit] for its content. */
    private class Stash(
        val ref: String,
        val commit: String,
    )

    /**
     * Moves the clone to `origin/main` and puts every local change back on top of it, with main's changes to its entries
     * merged field by field and to its texts key by key. A draft of a [merged] pull request that equals what was last
     * pushed takes main's version. A draft in conflict with main keeps its base, so the conflict stays. A pull that fails
     * leaves the clone as it was.
     */
    private fun pull(merged: List<Drafts.Review>) {
        val taken = merged.flatMap { it.drafts }.toSet() - Drafts.ids(pack)
        val prefix = pack.relativeTo(repository).invariantSeparatorsPath
        val skipped = Drafts.files(pack, taken).map { "$prefix/$it" }.toSet()
        val drafts = Drafts.list(pack)
        val bases = drafts.mapNotNull { draft -> Drafts.base(pack, draft.id)?.let { draft.id to it } }.toMap()
        val owner = Texts.owner(pack)

        fun text(path: String) = path.startsWith("$prefix/") && Texts.isText(path.removePrefix("$prefix/"))
        val (name, email) = author()
        git(
            repository,
            listOf("-c", "user.name=$name", "-c", "user.email=$email", "stash", "push", "-q", "--include-untracked", "-m", PULL),
        )
        val stash = named(PULL)
        if (stash == null) {
            git(repository, listOf("merge", "-q", "--ff-only", "origin/main"))
            return Drafts.writeBases(pack, emptyMap())
        }
        val commit = stash.commit
        try {
            git(repository, listOf("merge", "-q", "--ff-only", "origin/main"))
            val glyphs = "$prefix/glyphs.json"
            val changed = paths(listOf("diff", "--name-only", "-z", "$commit^1", commit)) - skipped
            val kept = paths(listOf("ls-tree", "-r", "-z", "--name-only", commit)).toSet()
            val (restored, deleted) = (changed - glyphs).filterNot(::text).partition { it in kept }
            val untracked = (untracked(commit) - skipped).filterNot(::text)
            if (restored.isNotEmpty()) git(repository, listOf("restore", "--source=$commit", "--worktree", "--") + restored)
            if (untracked.isNotEmpty()) git(repository, listOf("restore", "--source=$commit^3", "--worktree", "--") + untracked)
            AtomicFiles.write(deleted.map { repository.resolve(it) }.filter { it.exists() }.associateWith { null })
            if (glyphs in changed) {
                val mine = Drafts.glyphs(pack, commit)
                val main = Drafts.glyphs(pack, "HEAD")
                val ids = Drafts.changedIds(Drafts.glyphs(pack, "$commit^1"), mine) - taken
                if (ids.isNotEmpty()) {
                    NewTextures.edit(pack, null, 0, 0) { root ->
                        ids.forEach { Drafts.write(root, Drafts.merge(Drafts.glyphs(pack, bases[it] ?: "$commit^1"), mine, main, it), it) }
                    }
                }
            }
            // The stash keeps a new language file among its untracked files, in its third parent
            val mine = Texts.at(pack, commit) + Texts.at(pack, "$commit^3")
            val keys = Texts.changedKeys(Texts.at(pack, "$commit^1"), mine).filter { owner(it) !in taken }
            for ((base, group) in keys.groupBy { bases[owner(it)] ?: "$commit^1" }) {
                Texts.put(pack, Texts.merge(Texts.at(pack, base), mine, Texts.at(pack, "HEAD"), group.toSet()).first, group.toSet())
            }
        } catch (failure: Exception) {
            runCatching { putBack(stash) }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
        git(repository, listOf("stash", "drop", "-q", stash.ref))
        Drafts.writeBases(
            pack,
            drafts
                .filter { it.conflict != null }
                .mapNotNull { draft ->
                    bases[draft.id]?.let { draft.id to it }
                }.toMap(),
        )
    }

    /**
     * Puts back the changes of a pull that stopped halfway, with the edits made since on top, and returns whether there
     * were any. Edits that clash with them stay in a stash of their own beside the pull's, and the sync is refused.
     */
    private fun resume(): Boolean {
        named(EDITS)?.let { throw GradleException(clash(it)) }
        val stash = named(PULL) ?: return false
        if (git(repository, listOf("status", "--porcelain")).isBlank()) {
            putBack(stash)
            return true
        }
        val (name, email) = author()
        git(
            repository,
            listOf("-c", "user.name=$name", "-c", "user.email=$email", "stash", "push", "-q", "--include-untracked", "-m", EDITS),
        )
        val edits = named(EDITS)!!
        val pull = named(PULL)!!
        putBack(pull, keep = true)
        try {
            git(repository, listOf("stash", "apply", "-q", edits.ref))
        } catch (failure: Exception) {
            val refusal = GradleException(clash(edits), failure)
            runCatching { clear(pull, edits) }.onFailure { refusal.addSuppressed(it) }
            throw refusal
        }
        git(repository, listOf("stash", "drop", "-q", edits.ref))
        git(repository, listOf("stash", "drop", "-q", named(PULL)!!.ref))
        return true
    }

    /** Why a sync is refused while the stash [edits] holds edits that clash with a pull that stopped halfway. */
    private fun clash(edits: Stash) =
        "The edits made while a pull stopped halfway clash with its changes: they are in the stash \"$EDITS\" (${edits.ref}), " +
            "the pull's changes in the stash \"$PULL\". Apply both by hand and drop them."

    /**
     * Puts the clone back to where it was when the pull stashed its changes, with every one of them, and drops the stash
     * unless [keep] is set.
     */
    private fun putBack(
        stash: Stash,
        keep: Boolean = false,
    ) {
        clear(stash)
        git(repository, listOf("stash", if (keep) "apply" else "pop", "-q", stash.ref))
    }

    /** Resets the clone to the commit that [stash] was made on, without the untracked files of it and of [others]. */
    private fun clear(
        stash: Stash,
        vararg others: Stash,
    ) {
        git(repository, listOf("reset", "-q", "--hard", "${stash.commit}^1"))
        val files = (listOf(stash) + others).flatMap { untracked(it.commit) }.distinct()
        AtomicFiles.write(files.map { repository.resolve(it) }.filter { it.exists() }.associateWith { null })
    }

    /** The stash entry with the message [message], or null where there is none. */
    private fun named(message: String): Stash? =
        git(repository, listOf("stash", "list", "-z", "--format=%gd%x01%H%x01%gs"))
            .split('\u0000')
            .map { it.split('\u0001') }
            .firstOrNull { it.size == 3 && it[2].endsWith(": $message") }
            ?.let { Stash(it[0], it[1]) }

    /** The untracked files that the stash [commit] holds. */
    private fun untracked(commit: String): List<String> {
        val parents = git(repository, listOf("rev-list", "--parents", "-n", "1", commit)).trim().split(' ')
        return if (parents.size < 4) emptyList() else paths(listOf("ls-tree", "-r", "-z", "--name-only", "$commit^3"))
    }

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

/** The outcomes of a check that fail it, a check run's conclusion or a commit status's state. */
private val FAILED = setOf("FAILURE", "ERROR", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE")

/** The states of a commit status that is not done yet. */
private val PENDING = setOf("PENDING", "EXPECTED")

/** How many characters of a review comment the designer shows. */
private const val COMMENT = 280

/** The message of the stash that a pull keeps the clone's changes in. */
private const val PULL = "nordtal-designer-pull"

/** The message of the stash that keeps the edits made while a pull stopped halfway. */
private const val EDITS = "nordtal-designer-edits"

/** Runs `:resource-pack:check` with the Gradle wrapper of the work tree [root]. */
private fun checkPack(root: File) {
    val windows = System.getProperty("os.name").startsWith("Windows")
    if (windows) {
        run(
            "cmd",
            root,
            listOf("/c", "gradlew.bat", "-q", ":resource-pack:check"),
        )
    } else {
        run("sh", root, listOf("gradlew", "-q", ":resource-pack:check"))
    }
}
