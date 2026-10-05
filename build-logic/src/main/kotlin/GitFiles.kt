package eu.nordtal.season.build

import org.gradle.api.GradleException
import java.io.File

/** Returns the paths Git tracks under [pathspecs], relative to [root]. */
internal fun trackedFiles(
    root: File,
    pathspecs: List<String>,
): List<String> {
    val process = ProcessBuilder(listOf("git", "ls-files", "-z", "--") + pathspecs).directory(root).start()
    process.outputStream.close()
    val files =
        process.inputStream
            .bufferedReader()
            .use { it.readText() }
            .split('\u0000')
            .filter { it.isNotBlank() }
    if (process.waitFor() != 0) throw GradleException("git ls-files failed in $root")
    return files
}

/** Returns the text of [file], or null for a directory, a binary or anything over a megabyte. */
internal fun readText(file: File): String? {
    if (!file.isFile || file.length() > 1_000_000) return null
    val text = file.readText()
    return if ('\u0000' in text) null else text
}
