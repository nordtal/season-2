package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Fails with every [PackRules] finding in the pack at [assets]. */
@DisableCachingByDefault(because = "Cheaper to run than to cache")
abstract class CheckPack : DefaultTask() {
    /** The assembled pack's `assets`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assets: DirectoryProperty

    init {
        group = "verification"
        description = "Checks the pack's images against its fonts."
    }

    @TaskAction
    fun check() {
        val findings = PackRules.findings(assets.get().asFile)
        if (findings.isEmpty()) return
        throw GradleException(
            "The resource pack has ${findings.size} problem(s):\n" + findings.joinToString("\n") { "  - $it" },
        )
    }
}
