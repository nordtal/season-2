package eu.nordtal.season.build

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes the [PackModel] of the resource pack as one JSON file to [model], for Nordtal Designer.
 *
 * The file is UTF-8, ends in a newline and keeps the key order of `resource-pack/README.md`, which documents it. It
 * marks the drafts, the textures that differ from the last commit, so it reads git on every run.
 */
@DisableCachingByDefault(because = "Reads which files git holds as changed")
abstract class DescribePack : DefaultTask() {
    /** The resource pack's directory. Every path in the model is relative to it. */
    @get:Internal
    abstract val root: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allocation: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    /** `resource-pack/art`, which may not exist. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val art: DirectoryProperty

    @get:OutputFile
    abstract val model: RegularFileProperty

    init {
        group = "resource pack"
        description = "Writes the resource pack as one JSON model for Nordtal Designer."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun describe() {
        val glyphs =
            try {
                GlyphAllocation.read(allocation.get().asFile)
            } catch (e: IllegalStateException) {
                throw GradleException(e.message ?: "glyphs.json cannot be read", e)
            }
        val file = model.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(PackModel.of(glyphs, root.get().asFile, changedFiles(root.get().asFile)))) + "\n",
        )
        logger.lifecycle("Wrote the pack model to $file")
    }
}
