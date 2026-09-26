package eu.nordtal.s2.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes the glyphs a message can name into [target] as `manifest.json` plus one `<name>.png` each.
 *
 * Reads [names] against the pack's `minecraft:default` only: the `nordtal:` fonts are layout
 * pieces a message never names. A name the font does not declare fails the build here;
 * `GlyphNamesTest` in `:common` says the same on `check`.
 */
abstract class GlyphManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val names: RegularFileProperty

    /** The pack's `src/assets`, holding `minecraft/font/default.json` and the textures it names. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assets: DirectoryProperty

    @get:OutputDirectory
    abstract val target: DirectoryProperty

    @TaskAction
    fun write() {
        val assetsDir = assets.get().asFile
        val font = JsonSlurper().parse(assetsDir.resolve("minecraft/font/default.json")) as Map<*, *>
        val providers =
            (font["providers"] as List<*>)
                .map { it as Map<*, *> }
                .filter { it["type"] == "bitmap" }
        val byCodePoint =
            providers
                .flatMap { provider ->
                    (provider["chars"] as List<*>).flatMap { row -> (row as String).codePoints().toArray().map { it to provider } }
                }.toMap()

        val out = target.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val entries =
            names
                .get()
                .asFile
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val (name, hex) = line.split(Regex("\\s+"))
                    val codePoint = hex.toInt(16)
                    val provider =
                        byCodePoint[codePoint]
                            ?: error("glyph $name (U+$hex) is not declared in minecraft:default")
                    val (namespace, path) = (provider["file"] as String).split(":", limit = 2)
                    assetsDir.resolve("$namespace/textures/$path").copyTo(out.resolve("$name.png"))
                    linkedMapOf(
                        "name" to name,
                        "codePoint" to codePoint,
                        "height" to provider["height"],
                        "ascent" to provider["ascent"],
                        "image" to "$name.png",
                    )
                }
        out.resolve("manifest.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(entries)) + "\n")
    }
}
