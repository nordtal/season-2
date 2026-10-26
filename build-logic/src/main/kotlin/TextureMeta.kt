package eu.nordtal.season.build

import java.io.File
import javax.imageio.ImageIO

/**
 * Reads and writes the `.png.mcmeta` of a texture in the pack at [pack]: its `animation` and its `gui.scaling`.
 *
 * Each write keeps every other member and removes an `.mcmeta` left empty. [scale] returns the `.mcmeta` it wrote or
 * removed, and [animate] an [Animated] that holds it.
 */
object TextureMeta {
    /** The milliseconds of one game tick, the unit of `frametime` and `time`. */
    private const val TICK = 50

    /** The scaling types of a GUI sprite. */
    val SCALINGS = listOf("stretch", "tile", "nine_slice")

    /**
     * Writes the `animation` of [texture], whose PNG is a column of one frame per entry of [frames], each its duration in
     * milliseconds, and held [from] frames before. One frame removes the animation. `interpolate` stays, `width` while it is
     * the PNG's width, and the order in `frames` while the count is [from], each changed duration written into its entries.
     */
    fun animate(
        pack: File,
        texture: String,
        frames: List<Int>,
        from: Int?,
    ): Animated {
        require(frames.isNotEmpty() && frames.all { it > 0 }) { "Each frame lasts at least 1 millisecond." }
        val png = png(pack, texture)
        val image = ImageIO.read(png) ?: throw IllegalArgumentException("${relative(pack, png)} is no PNG that can be read.")
        require(image.height % frames.size == 0) {
            "${relative(pack, png)} is ${image.height} pixels high, which ${frames.size} frames do not divide."
        }
        val notices = mutableListOf<String>()
        val written =
            change(pack, png) { meta ->
                val old = meta.remove("animation") as? Map<*, *>
                if (frames.size == 1) return@change
                val ticks = frames.map { maxOf(1, Math.round(it / TICK.toDouble()).toInt()) }
                val width = old?.get("width")?.takeIf { it == image.width }
                if (old?.get("width") != null && width == null) notices += "dropped width"
                val read = old?.let { order(it) }
                val order = read?.takeIf { from == ticks.size && it.all { (i, _) -> i in ticks.indices } }
                if (read != null && order == null) notices += if (from == ticks.size) "dropped frames" else "changed frames"
                val frametime = (old?.get("frametime") as? Int) ?: 1
                val played = order?.map { it.first }?.toSet().orEmpty()
                val changed = played.filter { ticks[it] != time(order, it, frametime) }
                if (order != null) ticks.indices.filter { it !in played && ticks[it] != frametime }.forEach { notices += "unplayed $it" }
                val entries = order?.map { (i, time) -> i to if (i in changed) ticks[i] else time } ?: ticks.indices.map { it to ticks[it] }
                val animation = OxfmtJson.Object()
                val kept = order != null && changed.isEmpty()
                if (kept) {
                    old!!["frametime"]?.let { animation["frametime"] = it }
                } else if (entries.map { it.second }.distinct().size == 1) {
                    animation["frametime"] = entries[0].second
                }
                val height = image.height / frames.size
                if (width != null) animation["width"] = width
                if (width != null || height != image.width) animation["height"] = height
                old?.get("interpolate")?.let { animation["interpolate"] = it }
                if (kept) {
                    animation["frames"] = old!!["frames"]
                } else if (animation["frametime"] == null) {
                    animation["frames"] = entries.map { (index, time) -> linkedMapOf("index" to index, "time" to time) }
                } else if (order != null) {
                    animation["frames"] = entries.map { it.first }
                }
                meta["animation"] = animation
            }
        return Animated(written, notices)
    }

    /**
     * The `.mcmeta` that [animate] wrote or removed, and its [notices]: `dropped width`, `dropped frames`, `changed frames`
     * or `unplayed` and the index of a frame whose new duration the order never plays.
     */
    data class Animated(
        val written: List<File>,
        val notices: List<String>,
    )

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
            // Minecraft refuses a nine_slice whose borders leave no middle in the size it declares
            val (left, top, right, bottom) = border
            require(left + right < width!!) {
                "The left and right border of $left and $right pixels leave no middle in the width of $width pixels."
            }
            require(top + bottom < height!!) {
                "The top and bottom border of $top and $bottom pixels leave no middle in the height of $height pixels."
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
     * The frames of an image [width] by [height] pixels that its `animation` cuts, each once as `x`, `y`, `width`, `height`
     * and `duration` in milliseconds, the time of the first entry of `frames` that plays it, or null when its frames do not
     * divide the image or an entry lies outside it.
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
        val order = order(animation)
        if (order != null && order.any { it.first !in 0 until count }) return null
        return (0 until count).map { index ->
            linkedMapOf(
                "x" to index % columns * frameWidth,
                "y" to index / columns * frameHeight,
                "width" to frameWidth,
                "height" to frameHeight,
                "duration" to time(order, index, frametime) * TICK,
            )
        }
    }

    /** Each entry of the `frames` of [animation] as its index and its time in ticks, or null without `frames`. */
    private fun order(animation: Map<*, *>): List<Pair<Int, Int>>? {
        val frametime = (animation["frametime"] as? Int) ?: 1
        return (animation["frames"] as? List<*>)?.map {
            if (it is Map<*, *>) (it["index"] as? Int ?: -1) to ((it["time"] as? Int) ?: frametime) else (it as? Int ?: -1) to frametime
        }
    }

    /** The time in ticks of the frame [index]: that of the first entry of [order] that plays it, else [frametime]. */
    private fun time(
        order: List<Pair<Int, Int>>?,
        index: Int,
        frametime: Int,
    ) = order?.firstOrNull { it.first == index }?.second ?: frametime

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
