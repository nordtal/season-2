package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails on an em or en dash in a file that Git tracks under [pathspecs], and on a hyphen with a space on each side
 * in a comment or in Markdown prose. Code spans and list markers do not count.
 */
@DisableCachingByDefault(because = "Which files Git tracks is not a declarable input")
abstract class CheckNoDashPunctuation : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Internal
    abstract val pathspecs: ListProperty<String>

    /** When false, the task reports only the number of findings. */
    @get:Internal
    abstract val enforced: Property<Boolean>

    init {
        group = "verification"
        description = "Fails on dash punctuation in tracked files, comments and Markdown."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile
        if (!root.resolve(".git").exists()) return
        val findings =
            trackedFiles(root, pathspecs.get())
                .filterNot { it.substringAfterLast('/') in GENERATED }
                .flatMap { path -> findingsIn(path, root.resolve(path)).map { "    $path:$it" } }
        if (findings.isEmpty()) return
        if (enforced.get()) throw GradleException("Dash punctuation in the repository:\n" + findings.joinToString("\n"))
        logger.lifecycle("$path: ${findings.size} dash findings, not yet enforced.")
    }

    private fun findingsIn(
        path: String,
        file: java.io.File,
    ): List<String> {
        val lines = readText(file)?.lines() ?: return emptyList()
        // An applied migration stays unchanged, because Flyway refuses a migration with a changed checksum.
        val syntax = if ("/db/migration/" in path) Syntax.NONE else Syntax.of(path, lines.firstOrNull().orEmpty())
        val prose = syntax.prose(lines)
        return lines.indices.mapNotNull { index ->
            when {
                DASH.containsMatchIn(lines[index]) -> "${index + 1}: an em or en dash"
                prose[index].any { SPACED_HYPHEN.containsMatchIn(plain(it)) } -> "${index + 1}: ' - ' as punctuation"
                else -> null
            }
        }
    }

    /** Removes code spans and a leading list marker, which can hold a hyphen that is no punctuation. */
    private fun plain(text: String): String = LIST_MARKER.replaceFirst(CODE_SPAN.replace(text, "code"), "")

    /** Describes how a file type marks its comments, or marks Markdown prose. */
    private enum class Syntax {
        NONE,
        SLASH,
        HASH,
        PROPERTIES,
        SQL,
        XML,
        MARKDOWN,
        ;

        /** Returns the comment or prose text of each line. */
        fun prose(lines: List<String>): List<List<String>> =
            when (this) {
                NONE -> lines.map { emptyList() }
                SLASH -> blocks(lines, SLASH_OPEN, "*/")
                XML -> blocks(lines, Regex("""<!--"""), "-->")
                HASH -> lines.map { line -> HASH_COMMENT.find(line)?.let { listOf(it.groupValues[1]) }.orEmpty() }
                PROPERTIES -> lines.map { line -> PROPERTIES_COMMENT.find(line)?.let { listOf(it.groupValues[1]) }.orEmpty() }
                SQL -> lines.map { line -> SQL_COMMENT.find(line)?.let { listOf(it.groupValues[1]) }.orEmpty() }
                MARKDOWN -> markdown(lines)
            }

        private fun blocks(
            lines: List<String>,
            open: Regex,
            close: String,
        ): List<List<String>> {
            var inBlock = false
            return lines.map { line ->
                val pieces = mutableListOf<String>()
                var rest = line
                while (true) {
                    if (inBlock) {
                        val end = rest.indexOf(close)
                        pieces += BLOCK_PREFIX.replaceFirst(if (end < 0) rest else rest.substring(0, end), "")
                        if (end < 0) break
                        inBlock = false
                        rest = rest.substring(end + close.length)
                    } else {
                        val start = open.find(rest) ?: break
                        if (start.value == "//") {
                            pieces += rest.substring(start.range.last + 1)
                            break
                        }
                        inBlock = true
                        rest = rest.substring(start.range.last + 1).removePrefix("*")
                    }
                }
                pieces
            }
        }

        private fun markdown(lines: List<String>): List<List<String>> {
            var fenced = false
            return lines.map { line ->
                if (FENCE.containsMatchIn(line)) {
                    fenced = !fenced
                    emptyList()
                } else if (fenced) {
                    emptyList()
                } else {
                    listOf(line)
                }
            }
        }

        companion object {
            fun of(
                path: String,
                firstLine: String,
            ): Syntax {
                val name = path.substringAfterLast('/')
                return when {
                    name.startsWith("Dockerfile") || firstLine.startsWith("#!") -> {
                        HASH
                    }

                    else -> {
                        when (name.substringAfterLast('.')) {
                            "java", "kt", "kts", "ts", "tsx", "js", "mjs", "cjs", "css", "groovy" -> SLASH
                            "sh", "py", "yml", "yaml", "toml", "example", "dockerignore", "gitignore", "git-blame-ignore-revs" -> HASH
                            "properties" -> PROPERTIES
                            "sql" -> SQL
                            "xml", "html" -> XML
                            "md" -> MARKDOWN
                            else -> NONE
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** The files that a tool writes. The tool replaces them as a whole and nobody edits them. */
        val GENERATED = setOf("gradlew", "gradlew.bat")
        val DASH = Regex("[\u2013\u2014]")
        val SPACED_HYPHEN = Regex("""\S\s+-(\s|$)""")
        val CODE_SPAN = Regex("""`[^`]*`|\{@(code|literal) [^}]*}""")
        val LIST_MARKER = Regex("""^\s*([-*+]|\d+\.)\s""")
        val SLASH_OPEN = Regex("""(?<![^\s{(])(//|/\*)""")
        val BLOCK_PREFIX = Regex("""^\s*\*+(?!/)""")
        val HASH_COMMENT = Regex("""(?<!\S)#(.*)$""")
        val PROPERTIES_COMMENT = Regex("""^\s*[#!](.*)$""")
        val SQL_COMMENT = Regex("""(?<!\S)--(.*)$""")
        val FENCE = Regex("""^\s*(```|~~~)""")
    }
}
