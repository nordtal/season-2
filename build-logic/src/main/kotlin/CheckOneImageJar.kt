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
 * Every one of the four image modules has the same line:
 *
 * ```dockerfile
 * COPY build/libs/<module>-*.jar /app/app.jar
 * ```
 *
 * `steward-ui/.dockerignore` already knows what that costs and says so in as many words - "Two
 * matches is not an error - BuildKit picks one and says nothing" - and takes the thin jar back out
 * of the context so that only one remains. **What it cannot take out is an older version**, and
 * Gradle never removes one: `build/libs` keeps every jar it has ever written, so the commit that
 * moves `gradle.properties` from 0.8.7 to 0.9.0 leaves two behind and every later image build picks
 * one of them without saying which.
 *
 * Measured on the dev host on 2026-09-15, after `675fd29` had bumped the version: `steward-ui`,
 * `steward-worker` and `discord-bot` each held two, `steward-deployer` three. A hand-built image can
 * therefore carry the previous release, come up healthy, serve the interface, and differ only in a
 * feature that does not fire. That is a bad afternoon, and it is indistinguishable from a bug.
 *
 * So this counts what the context would really contain and refuses rather than choosing. It fires
 * exactly when the hazard is real - locally, after a version bump - and never in CI, where
 * `build/libs` is written once into an empty checkout.
 */
@DisableCachingByDefault(
    because = "Its result depends on files left behind by earlier builds, which are not an input"
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
        // Exactly the glob, minus what .dockerignore takes back out. Counting anything else would
        // be a guard against a different build than the one that runs.
        val matches = directory.listFiles()
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
                    " `gradlew :${artifact.get()}:clean` and build again."
            )
        }
    }
}
