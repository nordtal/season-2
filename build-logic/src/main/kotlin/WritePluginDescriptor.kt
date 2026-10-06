package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes `nordtal-plugin.json` and `nordtal/logo.png` into [target], the descriptor steward-agent reads out of a jar.
 *
 * The id is the service the module publishes its settings under; [editors] names, per group, the custom editor
 * Steward draws it with instead of the form built from its schema. [followsMessages] offers its bundles for editing.
 */
abstract class WritePluginDescriptor : DefaultTask() {
    @get:Input
    abstract val id: Property<String>

    @get:Input
    abstract val displayName: Property<String>

    @get:Input
    abstract val editors: MapProperty<String, String>

    @get:Input
    abstract val followsMessages: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val logo: RegularFileProperty

    @get:OutputDirectory
    abstract val target: DirectoryProperty

    @TaskAction
    fun write() {
        val directory = target.get().asFile
        directory.deleteRecursively()
        directory.resolve("nordtal").mkdirs()
        logo.get().asFile.copyTo(directory.resolve(LOGO))
        val editorJson =
            editors
                .get()
                .entries
                .sortedBy { it.key }
                .joinToString(", ") { "${quoted(it.key)}: ${quoted(it.value)}" }
        directory
            .resolve("nordtal-plugin.json")
            .writeText(
                "{\"id\": ${quoted(id.get())}, \"name\": ${quoted(displayName.get())}, " +
                    "\"logo\": ${quoted(LOGO)}, \"editors\": {$editorJson}, " +
                    "\"messages\": ${followsMessages.get()}}\n",
            )
    }

    private companion object {
        const val LOGO = "nordtal/logo.png"

        /** Module, group and editor names and a plain display name: quotes and backslashes are all JSON needs. */
        fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
