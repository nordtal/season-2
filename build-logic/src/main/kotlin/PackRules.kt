package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The rules a resource pack's own files can be held to without a server, a client or the Java modules.
 *
 * Every finding is a sentence for the person who drew the file.
 */
object PackRules {
    /** Sprites that hide a vanilla element and therefore must not carry a single pixel. */
    private val TRANSPARENT =
        listOf(
            "minecraft/textures/gui/sprites/boss_bar/white_background.png",
            "minecraft/textures/gui/sprites/boss_bar/white_progress.png",
            "minecraft/textures/gui/sprites/container/slot_highlight_back.png",
            "minecraft/textures/gui/sprites/container/slot_highlight_front.png",
        )

    /** @return one sentence per problem in the pack whose `assets` directory is [assets], empty when there is none */
    fun findings(assets: File): List<String> {
        val images = mutableMapOf<File, BufferedImage?>()
        val findings = mutableListOf<String>()
        assets
            .walkTopDown()
            .filter { it.isFile && it.extension == "png" }
            .sorted()
            .forEach { file ->
                val image = read(file)
                images[file] = image
                if (image == null) findings += "${name(assets, file)} cannot be read as a PNG. Export it again as PNG."
            }
        fonts(assets).forEach { findings += fontFindings(assets, it, images) }
        TRANSPARENT.map { assets.resolve(it) }.forEach { file ->
            val image = images[file] ?: return@forEach
            if (hasPixels(image, 0, 0, image.width, image.height)) {
                findings += "${name(assets, file)} must stay fully transparent: it hides a part of the vanilla game."
            }
        }
        return findings
    }

    private fun fonts(assets: File): List<File> =
        assets
            .listFiles()
            .orEmpty()
            .flatMap {
                it
                    .resolve("font")
                    .listFiles()
                    .orEmpty()
                    .toList()
            }.filter { it.extension == "json" }
            .sorted()

    private fun fontFindings(
        assets: File,
        font: File,
        images: Map<File, BufferedImage?>,
    ): List<String> {
        val parsed =
            runCatching { JsonSlurper().parse(font) }
                .getOrElse { return listOf("${name(assets, font)} is not valid JSON: ${it.message}") }
        val providers = (parsed as? Map<*, *>)?.get("providers") as? List<*>
        if (providers == null || providers.any { it !is Map<*, *> }) {
            return listOf("${name(assets, font)} has no list of providers. Ask a developer.")
        }
        return providers
            .map { it as Map<*, *> }
            .filter { it["type"] == "bitmap" }
            .flatMap { provider -> bitmapFindings(assets, font, provider, images) }
    }

    private fun bitmapFindings(
        assets: File,
        font: File,
        provider: Map<*, *>,
        images: Map<File, BufferedImage?>,
    ): List<String> {
        val id = provider["file"] as? String
        val chars = provider["chars"] as? List<*>
        if (id == null || chars.isNullOrEmpty() || chars.any { it !is String || it.isEmpty() }) {
            return listOf("${name(assets, font)} has a bitmap provider without a texture or without glyphs. Ask a developer.")
        }
        val file = texture(assets, id)
        val texture = name(assets, file)
        if (!file.isFile) return listOf("$texture is missing, and ${name(assets, font)} needs it.")
        val image = images[file] ?: return emptyList()
        val grid = chars.map { (it as String).codePoints().toArray() }
        val columns = grid.maxOf { it.size }
        if (grid.any { it.size != columns }) {
            return listOf("${name(assets, font)} gives $texture rows of different lengths. Ask a developer.")
        }
        if (image.width % columns != 0 || image.height % grid.size != 0) {
            return listOf(
                "$texture is ${image.width} x ${image.height} px, but it is cut into $columns columns and " +
                    "${grid.size} rows, so its width must divide by $columns and its height by ${grid.size}.",
            )
        }
        val width = image.width / columns
        val height = image.height / grid.size
        return grid.flatMapIndexed { row, codePoints ->
            codePoints.withIndex().mapNotNull { (column, codePoint) ->
                val drawn = hasPixels(image, column * width, row * height, width, height)
                val where = if (grid.size == 1 && columns == 1) "the image" else "row ${row + 1}, column ${column + 1}"
                when {
                    codePoint == ' '.code -> null
                    codePoint == 0 && drawn -> "$texture: $where has pixels, but no glyph uses that cell, so nobody sees them."
                    codePoint != 0 && !drawn -> "$texture: $where is empty, so glyph U+%04X would show as a gap.".format(codePoint)
                    else -> null
                }
            }
        }
    }

    private fun texture(
        assets: File,
        id: String,
    ): File {
        val namespace = id.substringBefore(':', "minecraft")
        return assets.resolve("$namespace/textures/${id.substringAfter(':')}")
    }

    private fun read(file: File): BufferedImage? = runCatching { ImageIO.read(file) }.getOrNull()

    private fun name(
        assets: File,
        file: File,
    ): String = file.relativeTo(assets).invariantSeparatorsPath

    private fun hasPixels(
        image: BufferedImage,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean = (y until y + height).any { row -> (x until x + width).any { column -> image.getRGB(column, row) ushr 24 != 0 } }
}
