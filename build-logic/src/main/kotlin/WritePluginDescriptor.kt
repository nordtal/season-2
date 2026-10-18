package eu.nordtal.season.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes `nordtal-plugin.json` into [target]. steward-agent reads this descriptor from a jar.
 *
 * The id is the service that the module publishes its settings under. [editors] names, per group, the custom editor
 * that Steward draws instead of the form from the schema. [followsMessages] offers the bundles for editing.
 * A jar built outside a release sets [local]. Steward shows it, and an update run asks before it replaces the jar.
 */
abstract class WritePluginDescriptor : DefaultTask() {
    @get:Input
    abstract val id: Property<String>

    @get:Input
    abstract val editors: MapProperty<String, String>

    @get:Input
    abstract val followsMessages: Property<Boolean>

    @get:Input
    abstract val local: Property<Boolean>

    @get:OutputDirectory
    abstract val target: DirectoryProperty

    @TaskAction
    fun write() {
        val directory = target.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val editorJson =
            editors
                .get()
                .entries
                .sortedBy { it.key }
                .joinToString(", ") { "${quoted(it.key)}: ${quoted(it.value)}" }
        directory
            .resolve("nordtal-plugin.json")
            .writeText(
                "{\"id\": ${quoted(id.get())}, \"editors\": {$editorJson}, " +
                    "\"messages\": ${followsMessages.get()}" +
                    (if (local.get()) ", \"local\": true" else "") +
                    "}\n",
            )
    }

    private companion object {
        /** Escapes module, group and editor names. They need only quotes and backslashes escaped for JSON. */
        fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
