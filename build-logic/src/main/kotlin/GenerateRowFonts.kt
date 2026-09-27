package eu.nordtal.s2.build

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes `nordtal:gui_r0` … `gui_r<rows - 1>` from one template: the same font, every bitmap one row lower.
 *
 * A glyph's height is set only by its font's ascent, so row `r` subtracts `r * pitch` from every bitmap ascent.
 */
@CacheableTask
abstract class GenerateRowFonts : DefaultTask() {
    /** Row 0's font. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val template: RegularFileProperty

    /** How many rows a chest has. */
    @get:Input
    abstract val rows: Property<Int>

    /** The distance between two chest rows, in pixels. */
    @get:Input
    abstract val pitch: Property<Int>

    /** The pack root the fonts are written under, as `assets/nordtal/font/gui_rN.json`. */
    @get:OutputDirectory
    abstract val target: DirectoryProperty

    init {
        group = "build"
        description = "Writes the six chest-row fonts from their template."
    }

    @TaskAction
    fun generate() {
        val fonts = target.get().asFile.resolve("assets/nordtal/font")
        fonts.deleteRecursively()
        fonts.mkdirs()
        for (row in 0 until rows.get()) {
            @Suppress("UNCHECKED_CAST")
            val font = JsonSlurper().parse(template.get().asFile) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            (font["providers"] as List<MutableMap<String, Any?>>)
                .filter { it["type"] == "bitmap" }
                .forEach { it["ascent"] = (it["ascent"] as Number).toInt() - row * pitch.get() }
            fonts.resolve("gui_r$row.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(font)) + "\n")
        }
    }
}
