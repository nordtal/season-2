package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

/** Sets the season number in gradle.properties and rewrites every tracked file that spells the old number. */
@DisableCachingByDefault(because = "It rewrites the working tree")
abstract class ForkSeason : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Internal
    abstract val season: Property<String>

    @get:Internal
    @get:Option(option = "to", description = "The number of the new season")
    abstract val to: Property<String>

    init {
        group = "release"
        description = "Forks the checkout to the next season: sets season= and rewrites the names that carry it."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun fork() {
        val next = to.orNull ?: throw GradleException("Pass the new season with --to=<number>.")
        if (!next.matches(Regex("[1-9][0-9]*"))) throw GradleException("--to=$next is not a season number.")
        if (next == season.get()) throw GradleException("The checkout is already season $next.")
        val root = repositoryRoot.get().asFile
        val properties = root.resolve("gradle.properties")
        properties.writeText(properties.readText().replace(Regex("(?m)^season=.*$"), "season=$next"))
        val changed = SeasonNames.rewrite(root, trackedFiles(root, listOf(".")), next)
        logger.lifecycle("season=$next, ${changed.size} files rewritten:\n" + changed.joinToString("\n") { "    $it" })
    }
}
