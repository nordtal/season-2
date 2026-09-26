package eu.nordtal.s2.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** The comment rules of CONVENTIONS.md for sources Checkstyle does not read, such as TypeScript. */
@DisableCachingByDefault(because = "Cheaper to run than to cache")
abstract class CheckCommentShape : DefaultTask() {
    /** The files to check. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Paths in the report are relative to it. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** False only reports the count, while the module does not pass yet. */
    @get:Internal
    abstract val enforced: Property<Boolean>

    init {
        group = "verification"
        description = "Fails on dates, TODOs, banners and multi-line inline comments."
    }

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile
        val findings =
            sources.files.sorted().flatMap { file ->
                findingsIn(file.readLines()).map { "    ${file.relativeTo(root)}:$it" }
            }
        if (findings.isEmpty()) return
        if (enforced.get()) throw GradleException("Comments that break CONVENTIONS.md:\n" + findings.joinToString("\n"))
        logger.lifecycle("$path: ${findings.size} comment findings, not yet enforced.")
    }

    private fun findingsIn(lines: List<String>): List<String> =
        lines.withIndex().flatMap { (index, line) ->
            val comment = COMMENT.find(line)?.groupValues?.get(1) ?: return@flatMap emptyList()
            val number = index + 1
            buildList {
                if (TODO.containsMatchIn(comment)) add("$number: no TODO, FIXME or XXX")
                if (DATE.containsMatchIn(comment)) add("$number: no dates in comments")
                if (BANNER.containsMatchIn(comment)) add("$number: no banner comments")
                if (LINE_COMMENT.matches(line) && index > 0 && LINE_COMMENT.matches(lines[index - 1])) {
                    add("$number: an inline comment is one line")
                }
            }
        }

    private companion object {
        val COMMENT = Regex("""(?://|^\s*/?\*+)(.*)$""")
        val LINE_COMMENT = Regex("""^\s*//.*$""")
        val TODO = Regex("""\b(TODO|FIXME|XXX)\b""")
        val DATE = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
        val BANNER = Regex("""-{4,}|={4,}|\*{4,}|#{4,}|/{4,}|_{4,}|~{4,}""")
    }
}
