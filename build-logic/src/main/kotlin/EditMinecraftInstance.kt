package eu.nordtal.season.build

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Prints the chosen [MinecraftInstance] as one JSON line (`describe`), or installs the bridge mod of the newest release
 * into it with [gh] and prints the jar's path (`bridge`). `resource-pack/README.md` documents both.
 */
@DisableCachingByDefault(because = "Reads and writes a Minecraft instance, outside the build")
abstract class EditMinecraftInstance : DefaultTask() {
    /** `describe` or `bridge`. */
    @get:Input
    abstract val kind: Property<String>

    /** The file that [MinecraftInstanceChooser] writes. */
    @get:Internal
    abstract val instanceFile: RegularFileProperty

    /** The clone, whose remote names the repository of the release. */
    @get:Internal
    abstract val repository: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val gh: Property<String>

    init {
        group = "resource pack"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val store = instanceFile.get().asFile
        when (val kind = kind.get()) {
            "describe" -> logger.quiet(JsonOutput.toJson(MinecraftInstance.describe(store)))
            "bridge" -> logger.quiet(MinecraftInstance.installBridge(store, gh.getOrElse("gh"), repository.get().asFile).absolutePath)
            else -> throw GradleException("kind is describe or bridge, not $kind.")
        }
    }
}
