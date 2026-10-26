package eu.nordtal.season.build

import org.gradle.api.GradleException
import java.io.File

/**
 * Runs `git` with [arguments] in [root] and returns what it prints. Its error output is kept apart and goes into the
 * failure, so it never mixes into the result.
 *
 * @throws java.io.IOException when git cannot be started
 */
internal fun git(
    root: File,
    arguments: List<String>,
): String = run("git", root, arguments)

/**
 * Runs [program] with [arguments] in [root] and returns what it prints, as [git] does. The failure names the program by
 * its file name.
 *
 * @throws java.io.IOException when the program cannot be started
 */
internal fun run(
    program: String,
    root: File,
    arguments: List<String>,
): String {
    val errors = File.createTempFile("run", ".err")
    try {
        val process = ProcessBuilder(listOf(program) + arguments).directory(root).redirectError(errors).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException(
                "`${File(program).name} ${arguments.joinToString(" ")}` failed with exit code $exit in $root:\n${errors.readText().trim()}",
            )
        }
        return output
    } finally {
        errors.delete()
    }
}

/** The files under [pathspecs] that git tracks, and with [untracked] also those it would add, that no ignore rule matches. */
internal fun trackedFiles(
    root: File,
    pathspecs: List<String>,
    untracked: Boolean = false,
): List<String> {
    val others = if (untracked) listOf("--cached", "--others", "--exclude-standard") else emptyList()
    return git(root, listOf("ls-files", "-z") + others + "--" + pathspecs)
        .split('\u0000')
        .filter { it.isNotBlank() }
}

/** Returns the text of [file], or null for a directory, a binary file or a file over one megabyte. */
internal fun readText(file: File): String? {
    if (!file.isFile || file.length() > 1_000_000) return null
    val text = file.readText()
    return if ('\u0000' in text) null else text
}

/**
 * The files under [root] whose content differs from the last commit: changed, added or deleted ones, and those git would
 * add. Each path is relative to [root].
 */
internal fun changedFiles(root: File): Set<String> =
    (
        git(root, listOf("diff", "--name-only", "-z", "--relative", "HEAD", "--", ".")) + "\u0000" +
            git(root, listOf("ls-files", "-z", "--others", "--exclude-standard", "--", "."))
    ).split('\u0000')
        .filter { it.isNotBlank() }
        .toSet()
