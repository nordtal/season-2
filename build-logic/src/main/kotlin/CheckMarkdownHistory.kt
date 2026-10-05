package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails when a Markdown file Git tracks under [pathspecs] tells history: a date, "used to", "formerly",
 * "previously", "originally" or "before V21". A README states the present. Fenced code and code spans are skipped.
 */
@DisableCachingByDefault(because = "Which files Git tracks is not a declarable input")
abstract class CheckMarkdownHistory : DefaultTask() {
    /** The checkout root; `git` runs there. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** Git pathspecs relative to the root, `:(exclude)` entries included. */
    @get:Internal
    abstract val pathspecs: ListProperty<String>

    init {
        group = "verification"
        description = "Fails when tracked Markdown tells history instead of the present state."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile
        if (!root.resolve(".git").exists()) return
        val findings =
            trackedFiles(root, pathspecs.get())
                .filter { it.endsWith(".md") }
                .flatMap { path -> findingsIn(root.resolve(path)).map { "    $path:$it" } }
        if (findings.isEmpty()) return
        throw GradleException("Markdown that tells history instead of the present state:\n" + findings.joinToString("\n"))
    }

    private fun findingsIn(file: java.io.File): List<String> {
        val lines = readText(file)?.lines() ?: return emptyList()
        var fenced = false
        return lines.withIndex().mapNotNull { (index, line) ->
            if (FENCE.containsMatchIn(line)) {
                fenced = !fenced
                return@mapNotNull null
            }
            if (fenced) return@mapNotNull null
            HISTORY.find(CODE_SPAN.replace(line, "code"))?.let { "${index + 1}: history: '${it.value}'" }
        }
    }

    private companion object {
        val FENCE = Regex("""^\s*(```|~~~)""")
        val CODE_SPAN = Regex("""`[^`]*`""")
        val HISTORY =
            Regex(
                """\b\d{4}-\d{2}-\d{2}\b|\bused to\b|\b(formerly|previously|originally)\b|\b(before|since|until|after) V\d+\b""",
                RegexOption.IGNORE_CASE,
            )
    }
}
