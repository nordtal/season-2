package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Fails when a tracked file spells a season number other than the one in gradle.properties. */
@DisableCachingByDefault(because = "Which files Git tracks is not a declarable input")
abstract class CheckSeasonNames : DefaultTask() {
    /** The checkout root; `git` runs there. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** The `season` property of gradle.properties. */
    @get:Internal
    abstract val season: Property<String>

    init {
        group = "verification"
        description = "Fails when a tracked file names another season than gradle.properties does."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile
        if (!root.resolve(".git").exists()) return
        val strays = SeasonNames.strays(root, trackedFiles(root, listOf(".")), season.get())
        if (strays.isEmpty()) return
        throw GradleException(
            "Names of another season than season=${season.get()} in gradle.properties:\n" +
                strays.joinToString("\n") +
                "\nA new season is forked with `sh gradlew forkSeason --to=<number>`.",
        )
    }
}
