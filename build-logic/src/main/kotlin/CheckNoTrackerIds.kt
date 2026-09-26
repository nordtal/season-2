package eu.nordtal.s2.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Fails when a file Git tracks under [pathspecs] names an issue-tracker ID. */
@DisableCachingByDefault(because = "Which files Git tracks is not a declarable input")
abstract class CheckNoTrackerIds : DefaultTask() {
    /** The checkout root; `git` runs there. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** Git pathspecs relative to the root, `:(exclude)` entries included. */
    @get:Internal
    abstract val pathspecs: ListProperty<String>

    /** False only reports the count, while the module does not pass yet. */
    @get:Internal
    abstract val enforced: Property<Boolean>

    init {
        group = "verification"
        description = "Fails when a tracked file mentions an issue-tracker ID."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile
        if (!root.resolve(".git").exists()) return
        val process = ProcessBuilder(listOf("git", "ls-files", "-z", "--") + pathspecs.get()).directory(root).start()
        process.outputStream.close()
        val files =
            process.inputStream
                .bufferedReader()
                .use { it.readText() }
                .split('\u0000')
                .filter { it.isNotBlank() }
        if (process.waitFor() != 0) throw GradleException("git ls-files failed in $root")

        val hits = files.flatMap { path -> hitsIn(root.resolve(path)).map { "    $path:$it" } }
        if (hits.isEmpty()) return
        if (enforced.get()) {
            throw GradleException("Issue-tracker IDs in the repository:\n" + hits.joinToString("\n"))
        }
        logger.lifecycle("$path: ${hits.size} issue-tracker IDs, not yet enforced.")
    }

    private fun hitsIn(file: java.io.File): List<String> {
        if (!file.isFile || file.length() > 1_000_000) return emptyList()
        val text = file.readText()
        if ('\u0000' in text) return emptyList()
        return text
            .lineSequence()
            .withIndex()
            .mapNotNull { (index, line) -> PATTERN.find(line)?.let { "${index + 1}: ${it.value}" } }
            .toList()
    }

    companion object {
        /** Every prefix the trackers of this project have used. */
        val PATTERN = Regex("""\b(steward|season-2-ops|season-2-ingame|season-2-community|workspace)/\d+\b|\bNT\d+-\d+\b""")
    }
}
