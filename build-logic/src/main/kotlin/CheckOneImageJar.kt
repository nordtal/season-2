package eu.nordtal.s2.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails when more than one jar matches the glob the module's `Dockerfile` copies.
 *
 * Every image module's `Dockerfile` copies with `build/libs/<module>-*.jar`, after
 * `.dockerignore` removes the thin jar. Gradle never deletes an old jar from `build/libs`, so a
 * version bump in `gradle.properties` leaves the previous one behind and a later image build
 * picks between them without saying which. This task counts what the build context would really
 * contain and refuses rather than letting BuildKit choose silently.
 */
@DisableCachingByDefault(
    because = "Its result depends on files left behind by earlier builds, which are not an input",
)
abstract class CheckOneImageJar : DefaultTask() {
    /** `build/libs`, the directory the Dockerfile's `COPY` globs into. */
    @get:Internal
    abstract val libraries: DirectoryProperty

    /** The module's artifact base name - the part before the version in the glob. */
    @get:Internal
    abstract val artifact: Property<String>

    init {
        group = "verification"
        description = "Fails when the Dockerfile's jar glob would match more than one file."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val directory = libraries.get().asFile
        if (!directory.isDirectory) {
            return
        }
        val prefix = "${artifact.get()}-"
        // Exactly the glob, minus what .dockerignore takes back out.
        val matches =
            directory
                .listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.filter { it.startsWith(prefix) && it.endsWith(".jar") && !it.endsWith("-thin.jar") }
                ?.sorted()
                .orEmpty()

        if (matches.size > 1) {
            throw GradleException(
                "${matches.size} jars in ${directory.path} match what the Dockerfile copies:\n" +
                    matches.joinToString("\n") { "  $it" } +
                    "\n\nThe COPY is a glob and BuildKit picks one of them without saying which, so" +
                    " the image could carry any of these. Gradle does not delete the jar of a" +
                    " version it no longer builds; the usual cause is a version bump in" +
                    " gradle.properties.\n\nDelete the ones that are not this build's, or run" +
                    " `gradlew :${artifact.get()}:clean` and build again.",
            )
        }
    }
}
