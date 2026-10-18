package eu.nordtal.season.build

import java.io.File

/** The spellings of the season number outside gradle.properties. The check compares them and the fork rewrites them. */
internal object SeasonNames {
    /** One spelling: the text before the number, the number and the text that must not follow it. */
    val PATTERN =
        Regex(
            """(?<![A-Za-z0-9])((?:nordtal-s|nordtal-season-|nordtal/season-|season-))(\d+)(?!\d)(?!-(?:ops|ingame|community))""",
        )

    fun strays(
        root: File,
        files: List<String>,
        season: String,
    ): List<String> =
        files.flatMap { path ->
            val text = readText(root.resolve(path)) ?: return@flatMap emptyList<String>()
            text
                .lineSequence()
                .withIndex()
                .flatMap { (index, line) ->
                    PATTERN
                        .findAll(line)
                        .filter { it.groupValues[2] != season }
                        .map { "    $path:${index + 1}: ${it.value}" }
                }.toList()
        }

    /** Rewrites every spelling in [files] to [season] and returns the files that changed. */
    fun rewrite(
        root: File,
        files: List<String>,
        season: String,
    ): List<String> =
        files.filter { path ->
            val file = root.resolve(path)
            val text = readText(file) ?: return@filter false
            val rewritten = PATTERN.replace(text) { it.groupValues[1] + season }
            (rewritten != text).also { if (it) file.writeText(rewritten) }
        }
}
