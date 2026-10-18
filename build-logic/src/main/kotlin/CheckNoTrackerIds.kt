package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Fails when a file that Git tracks under [pathspecs] names an issue-tracker ID. */
@DisableCachingByDefault(because = "Which files Git tracks is not a declarable input")
abstract class CheckNoTrackerIds : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Internal
    abstract val pathspecs: ListProperty<String>

    /** When false, the task reports only the number of findings. */
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
        val files = trackedFiles(root, pathspecs.get())

        val hits = files.flatMap { path -> hitsIn(root.resolve(path)).map { "    $path:$it" } }
        if (hits.isEmpty()) return
        if (enforced.get()) {
            throw GradleException("Issue-tracker IDs in the repository:\n" + hits.joinToString("\n"))
        }
        logger.lifecycle("$path: ${hits.size} issue-tracker IDs, not yet enforced.")
    }

    private fun hitsIn(file: java.io.File): List<String> {
        val text = readText(file) ?: return emptyList()
        return text
            .lineSequence()
            .withIndex()
            .mapNotNull { (index, line) -> PATTERN.find(line)?.let { "${index + 1}: ${it.value}" } }
            .toList()
    }

    companion object {
        /** The prefixes of the trackers of this project. */
        val PATTERN = Regex("""\b(steward|season-2-ops|season-2-ingame|season-2-community|workspace)/\d+\b|\bNT\d+-\d+\b""")
    }
}
