package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails when a test source under [pathspecs] names the build definition to find the repository root itself.
 * `RepositoryRoot` in `:common`'s test fixtures is the one place that does.
 */
@DisableCachingByDefault(because = "Which files Git tracks is not a declarable input")
abstract class CheckTestsFindNoRoot : DefaultTask() {
    /** The checkout root; `git` runs there. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** Git pathspecs relative to the root, `:(exclude)` entries included. */
    @get:Internal
    abstract val pathspecs: ListProperty<String>

    init {
        group = "verification"
        description = "Fails when a test finds the repository root by itself instead of through RepositoryRoot."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile
        if (!root.resolve(".git").exists()) return
        val hits =
            trackedFiles(root, pathspecs.get())
                .filter { it.endsWith(".java") || it.endsWith(".kt") }
                .filter { "/src/test/" in "/$it" || "/src/testFixtures/" in "/$it" }
                .filterNot { it == HELPER }
                .flatMap { path -> hitsIn(root.resolve(path)).map { "    $path:$it" } }
        if (hits.isEmpty()) return
        throw GradleException(
            "A test finds the repository root itself; RepositoryRoot in :common's test fixtures does that:\n" +
                hits.joinToString("\n"),
        )
    }

    private fun hitsIn(file: java.io.File): List<String> =
        readText(file)
            ?.lineSequence()
            ?.withIndex()
            ?.filter { (_, line) -> MARKER in line }
            ?.map { (index, _) -> "${index + 1}: names $MARKER" }
            ?.toList()
            .orEmpty()

    companion object {
        /** The file whose presence marks the root. */
        const val MARKER = "settings.gradle.kts"

        /** The one test fixture allowed to look for it. */
        const val HELPER = "common/src/testFixtures/java/eu/nordtal/season/common/RepositoryRoot.java"
    }
}
