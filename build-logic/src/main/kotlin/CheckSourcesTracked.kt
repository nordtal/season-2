package eu.nordtal.s2.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException

/**
 * Fails when a file Gradle compiles is a file Git does not have.
 *
 * A local build compiles the working tree; CI compiles the checkout. Anything present in one and
 * absent from the other makes those two different programs, and the difference surfaces as a
 * compile error on a machine that is not yours. The usual cause is an unanchored directory pattern
 * in `.gitignore` - `run/` matches the Java package `eu.nordtal.s2.updater.run` just as happily as
 * it matches `hunger-games/run` - and it is the worst kind of cause, because an *ignored* file is
 * not an untracked one: `git status` stays clean and nothing ever hints at it.
 *
 * So this asks Git the only question that matters: of everything under a source directory, is
 * anything both untracked and ignored? It runs as part of `check`, which is to say on every local
 * `./gradlew build` - before the commit that would have hidden the file, not after the release
 * that tripped over it.
 */
@DisableCachingByDefault(
    because = "Its result depends on .gitignore and the index, neither of which is a declarable input"
)
abstract class CheckSourcesTracked : DefaultTask() {

    /** Source directories of this project. Their contents are what Gradle would compile. */
    @get:Internal
    abstract val sourceDirectories: ConfigurableFileCollection

    /** The checkout root; `git` runs there, and pathspecs are relative to it. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    init {
        group = "verification"
        description = "Fails when a source file is ignored by Git and therefore missing from the repository."
        // .gitignore, the global ignore file and the index are all inputs no task can declare, so
        // there is no honest up-to-date check here. The work is one `git` call.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile
        if (!File(root, ".git").exists()) {
            logger.info("{}: {} is not a Git checkout, nothing to compare against", path, root)
            return
        }

        val pathspecs = sourceDirectories.files
            .filter { it.isDirectory }
            .map { root.toPath().relativize(it.toPath()).joinToString("/") }
            .sorted()
        if (pathspecs.isEmpty()) return

        val ignored = ignoredFilesUnder(root, pathspecs)
        if (ignored.isEmpty()) return

        throw GradleException(buildString {
            appendLine("Git ignores these files, so they are not in the repository:")
            ignored.forEach { appendLine("    $it") }
            appendLine()
            appendLine("They sit under a source directory covered by $path, so this build sees them and a")
            appendLine("build from a fresh checkout does not. Find the rule with")
            appendLine("    git check-ignore -v <path>")
            appendLine("and anchor it in .gitignore (`/*/run/`, never a bare `run/`), then `git add` the files.")
        })
    }

    /**
     * The paths under [pathspecs] that are untracked *and* ignored. Asking for both at once is what
     * keeps a tracked file that happens to match an ignore rule from being reported: that file is in
     * the repository, which is all this task cares about.
     */
    private fun ignoredFilesUnder(root: File, pathspecs: List<String>): List<String> {
        val command = listOf(
            "git", "ls-files", "--others", "--ignored", "--exclude-standard", "-z", "--"
        ) + pathspecs

        val process = try {
            ProcessBuilder(command).directory(root).redirectErrorStream(true).start()
        } catch (e: IOException) {
            logger.warn(
                "{}: could not run git ({}), so a source file missing from the repository would go unnoticed",
                path, e.message
            )
            return emptyList()
        }

        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("`${command.joinToString(" ")}` failed with exit code $exit:\n$output")
        }
        return output.split('\u0000').filter { it.isNotBlank() }
    }
}
