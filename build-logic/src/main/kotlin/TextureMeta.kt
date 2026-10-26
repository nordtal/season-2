package eu.nordtal.season.build

import java.io.File
import javax.imageio.ImageIO

/**
 * Reads and writes the `.png.mcmeta` of a texture in the pack at [pack]: its `animation` and its `gui.scaling`.
 *
 * Each write keeps every other member, removes an `.mcmeta` left empty, and returns the `.mcmeta` it wrote or removed.
 */
object TextureMeta {
    /** The milliseconds of one game tick, the unit of `frametime` and `time`. */
    private const val TICK = 50
    private val SCALINGS = listOf("stretch", "tile", "nine_slice")

    /**
     * Writes the `animation` of [texture], whose PNG is a column of one frame per entry of [frames], each its duration in
     * milliseconds. One frame removes the animation. `interpolate` stays.
     */
    fun animate(
        pack: File,
        texture: String,
        frames: List<Int>,
    ): List<File> {
        require(frames.isNotEmpty() && frames.all { it > 0 }) { "Each frame lasts at least 1 millisecond." }
        val png = png(pack, texture)
        val image = ImageIO.read(png) ?: throw IllegalArgumentException("${relative(pack, png)} is no PNG that can be read.")
        require(image.height % frames.size == 0) {
            "${relative(pack, png)} is ${image.height} pixels high, which ${frames.size} frames do not divide."
        }
        return change(pack, png) { meta ->
            val old = meta.remove("animation") as? Map<*, *>
            if (frames.size == 1) return@change
            val animation = OxfmtJson.Object()
            val ticks = frames.map { maxOf(1, Math.round(it / TICK.toDouble()).toInt()) }
            if (ticks.distinct().size == 1) {
                animation["frametime"] = ticks[0]
            } else {
                animation["frames"] = ticks.mapIndexed { index, time -> linkedMapOf("index" to index, "time" to time) }
            }
            old?.get("interpolate")?.let { animation["interpolate"] = it }
            val height = image.height / frames.size
            if (height != image.width) animation["height"] = height
            meta["animation"] = animation
        }
    }

    /**
     * Writes the `gui.scaling` of the GUI sprite [texture]: [type] `stretch` removes it, `tile` takes [width] and [height],
     * and `nine_slice` also takes the [border] as left, top, right and bottom and [stretchInner].
     */
    fun scale(
        pack: File,
        texture: String,
        type: String,
        width: Int?,
        height: Int?,
        border: List<Int>?,
        stretchInner: Boolean?,
    ): List<File> {
        require(type in SCALINGS) { "The scaling is stretch, tile or nine_slice, not $type." }
        val png = png(pack, texture)
        require(Identifier.parse(texture, ".png").second.startsWith("gui/sprites/")) {
            "$texture is no GUI sprite, which lies under textures/gui/sprites/."
        }
        if (type != "stretch") {
            require(
                width != null && height != null && width > 0 && height > 0,
            ) { "A $type scaling needs a width and a height of at least 1 pixel." }
        }
        if (type == "nine_slice") {
            require(border != null && border.size == 4 && border.all { it >= 0 }) { "A nine_slice border is four numbers of at least 0." }
            val image = ImageIO.read(png) ?: throw IllegalArgumentException("${relative(pack, png)} is no PNG that can be read.")
            val (left, top, right, bottom) = border
            require(left + right <= image.width) {
                "The left and right border of $left and $right pixels are wider than the sprite of ${image.width} pixels."
            }
            require(top + bottom <= image.height) {
                "The top and bottom border of $top and $bottom pixels are higher than the sprite of ${image.height} pixels."
            }
        }
        return change(pack, png) { meta ->
            val gui =
                (meta["gui"] as? Map<*, *>)?.let { OxfmtJson.Object().apply { it.forEach { (k, v) -> this[k as String] = v } } }
                    ?: OxfmtJson.Object()
            gui.remove("scaling")
            if (type != "stretch") {
                val scaling = OxfmtJson.Object()
                scaling["type"] = type
                scaling["width"] = width
                scaling["height"] = height
                if (type == "nine_slice") {
                    val sides = border!!
                    scaling["border"] =
                        if (sides.distinct().size == 1) {
                            sides[0]
                        } else {
                            linkedMapOf("left" to sides[0], "top" to sides[1], "right" to sides[2], "bottom" to sides[3])
                        }
                    if (stretchInner == true) scaling["stretch_inner"] = true
                }
                gui["scaling"] = scaling
            }
            if (gui.isEmpty()) meta.remove("gui") else meta["gui"] = gui
        }
    }

    /**
     * The frames that an `animation` of an image [width] by [height] pixels plays, each as `x`, `y`, `width`, `height` and
     * `duration` in milliseconds, or null when its frames do not divide the image or a frame index lies outside it.
     */
    fun frames(
        width: Int,
        height: Int,
        animation: Map<*, *>,
    ): List<Map<String, Int>>? {
        val frameWidth = (animation["width"] as? Int) ?: if (animation["height"] != null) width else minOf(width, height)
        val frameHeight = (animation["height"] as? Int) ?: if (animation["width"] != null) height else minOf(width, height)
        if (frameWidth <= 0 || frameHeight <= 0 || width % frameWidth != 0 || height % frameHeight != 0) return null
        val columns = width / frameWidth
        val count = columns * (height / frameHeight)
        val frametime = (animation["frametime"] as? Int) ?: 1
        val played =
            (animation["frames"] as? List<*>)?.map {
                if (it is Map<*, *>) (it["index"] as? Int ?: -1) to ((it["time"] as? Int) ?: frametime) else (it as? Int ?: -1) to frametime
            } ?: (0 until count).map { it to frametime }
        if (played.any { it.first !in 0 until count }) return null
        return played.map { (index, time) ->
            linkedMapOf(
                "x" to index % columns * frameWidth,
                "y" to index / columns * frameHeight,
                "width" to frameWidth,
                "height" to frameHeight,
                "duration" to time * TICK,
            )
        }
    }

    /** Applies [edit] to the `.mcmeta` of [png], and writes it, or removes it when it is left empty. */
    private fun change(
        pack: File,
        png: File,
        edit: (OxfmtJson.Object) -> Unit,
    ): List<File> {
        val file = File(png.path + ".mcmeta")
        val meta = if (file.isFile) OxfmtJson.read(file) as? OxfmtJson.Object else OxfmtJson.Object()
        requireNotNull(meta) { "${relative(pack, file)} holds no JSON object." }
        edit(meta)
        if (meta.isEmpty()) {
            if (!file.exists()) return emptyList()
            AtomicFiles.write(mapOf(file to null))
        } else {
            AtomicFiles.write(mapOf(file to OxfmtJson.asset(meta).toByteArray()))
        }
        return listOf(file)
    }

    private fun png(
        pack: File,
        texture: String,
    ): File {
        val (namespace, path) = Identifier.parse(texture, ".png")
        return pack.resolve("src/assets/$namespace/textures/$path").also {
            require(it.isFile) { "${relative(pack, it)} does not exist." }
        }
    }

    private fun relative(
        pack: File,
        file: File,
    ) = file.relativeTo(pack).invariantSeparatorsPath
}
