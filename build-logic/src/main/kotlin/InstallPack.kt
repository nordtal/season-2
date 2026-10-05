package eu.nordtal.season.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.Properties
import javax.inject.Inject

/**
 * Mirrors the pack into the chosen Minecraft instance as the folder pack [packName], and enables it there once.
 *
 * The folder is replaced wholesale. `options.txt` is left alone while the game runs, which would overwrite it.
 */
@DisableCachingByDefault(because = "Writes into a Minecraft instance, outside the build")
abstract class InstallPack : DefaultTask() {
    /** The assembled pack, holding `pack.mcmeta` and `assets`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pack: DirectoryProperty

    /** The file [MinecraftInstanceChooser] writes, read when the task runs. */
    @get:Internal
    abstract val instanceFile: RegularFileProperty

    /** The folder name under `resourcepacks`, which is also the name the game lists. */
    @get:Internal
    abstract val packName: Property<String>

    @get:Inject
    abstract val files: FileSystemOperations

    init {
        group = "resource pack"
        description = "Copies the pack into the chosen Minecraft instance."
    }

    @TaskAction
    fun install() {
        val instance = instance()
        val target = instance.resolve("resourcepacks").resolve(packName.get())
        files.sync {
            from(pack)
            into(target)
        }
        logger.lifecycle("Installed the pack into $target")
        logger.lifecycle(enable(instance))
        logger.lifecycle("In Minecraft, press F3+T to load the new version.")
    }

    private fun instance(): File {
        val store = instanceFile.get().asFile
        val chosen =
            store
                .takeIf { it.isFile }
                ?.let { file -> Properties().apply { file.reader().use { load(it) } }.getProperty("instance") }
                ?: throw GradleException("No Minecraft instance is chosen yet. Run 'pack: 1. choose Minecraft instance' first.")
        return File(chosen).takeIf { it.isDirectory }
            ?: throw GradleException("$chosen does not exist any more. Run 'pack: 1. choose Minecraft instance' again.")
    }

    private fun enable(instance: File): String {
        val entry = "file/${packName.get()}"
        val options = instance.resolve("options.txt")
        val manually = "Enable '${packName.get()}' once in Minecraft under Options > Resource Packs."
        if (!options.isFile) return "Minecraft has not been started in this instance yet. $manually"
        val lines = options.readLines()
        val index = lines.indexOfFirst { it.startsWith(RESOURCE_PACKS) }
        val enabled =
            if (index < 0) {
                mutableListOf("vanilla")
            } else {
                (JsonSlurper().parseText(lines[index].removePrefix(RESOURCE_PACKS)) as List<*>).map { it.toString() }.toMutableList()
            }
        if (entry in enabled) return "The pack is enabled in this instance."
        if (MinecraftProcesses.running()) return "Minecraft is running, so options.txt was left alone. $manually"
        // The last entry is on top.
        enabled += entry
        val line = RESOURCE_PACKS + JsonOutput.toJson(enabled)
        options.writeText(
            (if (index < 0) lines + line else lines.toMutableList().also { it[index] = line }).joinToString("\n", postfix = "\n"),
        )
        return "Enabled '${packName.get()}' in options.txt. It is active the next time Minecraft starts."
    }

    private companion object {
        const val RESOURCE_PACKS = "resourcePacks:"
    }
}
