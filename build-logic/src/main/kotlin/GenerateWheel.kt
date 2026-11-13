package eu.nordtal.season.build

import groovy.json.JsonSlurper
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * Writes the wooden wheel's item definitions, models and drawn textures into [pack], and its timetable into
 * [resources] as `nordtal/wheel/wheel.properties`. The turn variants are the most that fit the budget, never fewer
 * than four; the task prints the count with the raw and zipped size and the load estimate.
 */
@CacheableTask
abstract class GenerateWheel : VanillaClientTask() {
    /** The prizes the pack prepares: a JSON list of `item` and `amount`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prizes: RegularFileProperty

    /** The wheel's text document, whose `spin-button` label the off plate draws in each language. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val texts: RegularFileProperty

    @get:OutputDirectory
    abstract val pack: DirectoryProperty

    @get:OutputDirectory
    abstract val resources: DirectoryProperty

    init {
        description = "Generates the wooden wheel's turn for the prizes on its list."
    }

    @TaskAction
    fun generate() {
        val list =
            (JsonSlurper().parse(prizes.get().asFile) as List<*>).map {
                val prize = it as Map<*, *>
                WheelPrize((prize["item"] as String).removePrefix("minecraft:"), (prize["amount"] as Number).toInt())
            }
        val budget = ZipFile(jar()).use { WheelPack(WheelVanilla(it), list, labels(texts.get().asFile)).fit() }
        val root = pack.get().asFile.apply { deleteRecursively() }
        for ((path, bytes) in budget.files) File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
        val properties = File(resources.get().asFile.apply { deleteRecursively() }, "nordtal/wheel/wheel.properties")
        properties.parentFile.mkdirs()
        properties.writeText(budget.properties())
        logger.lifecycle(
            "wheel generated: variants=${budget.offsets.size} raw_bytes=${budget.raw} zipped_bytes=${budget.zipped} load_ms=${budget.loadMs().toLong()}",
        )
    }

    companion object {
        /** The `spin-button` label per language, German first: the off plate's fallback. */
        fun labels(document: File): Map<String, String> {
            val parts = (JsonSlurper().parse(document) as Map<*, *>)["parts"] as List<*>
            val part = parts.map { it as Map<*, *> }.single { it["slot"] == "spin-button" }
            val text = ((part["lines"] as List<*>).single() as Map<*, *>)["text"] as Map<*, *>
            return text.entries
                .map { it.key as String to it.value as String }
                .sortedBy {
                    if (it.first ==
                        "de"
                    ) {
                        ""
                    } else {
                        it.first
                    }
                }.toMap(LinkedHashMap())
        }
    }
}

/** The textures the build draws for the wheel, from vanilla's letters and the kit's colours. */
internal object WheelArt {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val SHADOW = 0xFF3F3F3F.toInt()
    private const val DISABLED = 0xFFBDBDBD.toInt()
    private const val FAINT = 0xFF8B8B8B.toInt()

    fun clear(): BufferedImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)

    /** Digit [d] as a stack count: white with vanilla's shadow one pixel right and down, 6 x 8. */
    fun digit(
        ascii: BufferedImage,
        d: Int,
    ): BufferedImage {
        val (rows, _) = WheelVanilla.glyph(ascii, '0' + d)
        val image = BufferedImage(6, 8, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 7) for (x in 0 until 5) if (rows[y][x]) image.setRGB(x + 1, y + 1, SHADOW)
        for (y in 0 until 7) for (x in 0 until 5) if (rows[y][x]) image.setRGB(x, y, WHITE)
        return image
    }

    /** The placeholder for a prize the pack does not list: a white question mark at twice its size, with its shadow. */
    fun unknown(ascii: BufferedImage): BufferedImage {
        val (rows, _) = WheelVanilla.glyph(ascii, '?')
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for ((colour, shift) in listOf(SHADOW to 2, WHITE to 0)) {
            for (y in 0 until 7) {
                for (x in 0 until 5) {
                    if (!rows[y][x]) continue
                    for (dy in 0..1) for (dx in 0..1) image.setRGB(3 + 2 * x + dx + shift, 2 * y + dy + shift, colour)
                }
            }
        }
        return image
    }

    /** The disabled plate, 75 x 17, with [label] centred in faint letters, its capitals from row 5, as a plate draws it. */
    fun offPlate(
        ascii: BufferedImage,
        label: String,
    ): BufferedImage {
        val image = BufferedImage(WheelLayout.PLATE_WIDTH, WheelLayout.PLATE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, DISABLED)
        val glyphs = label.map { WheelVanilla.glyph(ascii, it) }
        val width = glyphs.sumOf { it.second } - 1
        var pen = (image.width - width) / 2
        for ((rows, advance) in glyphs) {
            for (y in rows.indices) {
                for (x in rows[y].indices) {
                    if (rows[y][x] && pen + x < image.width &&
                        5 + y < image.height
                    ) {
                        image.setRGB(pen + x, 5 + y, FAINT)
                    }
                }
            }
            pen += advance
        }
        return image
    }

    fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
