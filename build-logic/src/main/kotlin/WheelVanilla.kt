package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.awt.image.BufferedImage
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * What the wheel reads from the client jar: each prize's item definition reduced to what the GUI draws, the GUI
 * display transform of each model, and the letters of `ascii.png` for the textures the build draws.
 */
internal class WheelVanilla(
    private val zip: ZipFile,
) {
    private val models = HashMap<String, Map<*, *>>()

    /** The item definition's model node of [key], an item id without `minecraft:`, or null for no such item. */
    fun definition(key: String): Map<*, *>? = read("assets/minecraft/items/$key.json")?.get("model") as? Map<*, *>

    /** The GUI display transform that the model [ref] or its nearest parent carries, as vanilla applies it. */
    fun display(ref: String): DoubleArray = displayMatrix(gui(ref))

    private fun gui(ref: String): Map<*, *>? {
        var model = model(ref)
        repeat(DEPTH) {
            ((model["display"] as? Map<*, *>)?.get("gui") as? Map<*, *>)?.let { return it }
            val parent = model["parent"] as? String ?: return null
            model = model(parent)
        }
        return null
    }

    private fun model(ref: String): Map<*, *> {
        val namespace = if (':' in ref) ref.substringBefore(':') else "minecraft"
        val path = ref.substringAfter(':')
        return models.getOrPut("$namespace:$path") { read("assets/$namespace/models/$path.json") ?: emptyMap<String, Any>() }
    }

    private fun read(path: String): Map<*, *>? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { JsonSlurper().parse(it) } as? Map<*, *>
    }

    /** Vanilla's `ascii.png`, 16 by 16 cells of 8 pixels. */
    fun ascii(): BufferedImage {
        val entry = zip.getEntry(ASCII) ?: throw IllegalStateException("the client jar has no $ASCII")
        return zip.getInputStream(entry).use { ImageIO.read(it) }
    }

    companion object {
        private const val DEPTH = 16
        private const val ASCII = "assets/minecraft/textures/font/ascii.png"

        /** `ItemTransform.apply` for the GUI: translate, rotate X, Y, Z, scale, then vanilla's shift by -0.5. */
        fun displayMatrix(gui: Map<*, *>?): DoubleArray {
            val shift = WheelMath.translate(-0.5, -0.5, -0.5)
            if (gui == null) return shift

            fun three(
                key: String,
                default: Double,
            ) = (gui[key] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(default, default, default)
            val t = three("translation", 0.0).map { (it * 0.0625).coerceIn(-5.0, 5.0) }
            val r = three("rotation", 0.0).map { Math.toRadians(it) }
            val s = three("scale", 1.0).map { it.coerceIn(-4.0, 4.0) }
            return WheelMath.times(
                WheelMath.translate(t[0], t[1], t[2]),
                WheelMath.rotateX(r[0]),
                WheelMath.rotateY(r[1]),
                WheelMath.rotateZ(r[2]),
                WheelMath.scale(s[0], s[1], s[2]),
                shift,
            )
        }

        /**
         * The definition [node] reduced to what a GUI slot shows of a plain stack: a display context select to its
         * `gui` case, any other select and a range to its fallback or first case, a condition to its false side. Models,
         * special models and composites stay, with their tints. A collapsed node's transformation moves onto its child.
         */
        fun leaf(node: Map<*, *>): Map<String, Any?> {
            val type = short(node["type"] as String)
            val own = node["transformation"]
            val child: Map<*, *>? =
                when (type) {
                    "select" -> {
                        val cases = (node["cases"] as List<*>).map { it as Map<*, *> }
                        val gui =
                            if (short(node["property"] as String) == "display_context") {
                                cases.firstOrNull { case -> whens(case).contains("gui") }?.get("model") as? Map<*, *>
                            } else {
                                null
                            }
                        gui ?: node["fallback"] as? Map<*, *> ?: cases.firstOrNull()?.get("model") as? Map<*, *> ?: EMPTY
                    }

                    "range_dispatch" -> {
                        node["fallback"] as? Map<*, *>
                            ?: ((node["entries"] as List<*>).firstOrNull() as? Map<*, *>)?.get("model") as? Map<*, *>
                            ?: EMPTY
                    }

                    "condition" -> {
                        node["on_false"] as Map<*, *>
                    }

                    else -> {
                        null
                    }
                }
            if (child != null) {
                val reduced = leaf(child)
                if (own == null) return reduced
                val inner = reduced["transformation"]?.let { WheelMath.transformation(it) } ?: WheelMath.identity()
                return reduced + ("transformation" to WheelMath.list(WheelMath.times(WheelMath.transformation(own), inner)))
            }
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in node) out[k as String] = v
            if (type == "composite") out["models"] = (node["models"] as List<*>).map { leaf(it as Map<*, *>) }
            return out
        }

        /** The model refs of [leaf] whose display transforms place it: a model's own, a special model's base. */
        fun refs(leaf: Map<*, *>): List<String> =
            when (short(leaf["type"] as String)) {
                "model" -> listOf(leaf["model"] as String)
                "special" -> listOf(leaf["base"] as String)
                "composite" -> (leaf["models"] as List<*>).flatMap { refs(it as Map<*, *>) }
                else -> emptyList()
            }

        /**
         * [leaf] with every model moved by the screen move [move], each by its own display transform: for a prize whose
         * parts sit under different display transforms. [above] is the product of the transformations above the node.
         */
        fun moved(
            leaf: Map<*, *>,
            vanilla: WheelVanilla,
            move: DoubleArray,
            above: DoubleArray = WheelMath.identity(),
        ): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in leaf) out[k as String] = v
            val own = leaf["transformation"]?.let { WheelMath.transformation(it) } ?: WheelMath.identity()
            when (short(leaf["type"] as String)) {
                "model", "special" -> {
                    val ref = refs(leaf).single()
                    val local = WheelMath.times(WheelMath.inverse(above), local(vanilla.display(ref), move), above, own)
                    out["transformation"] = WheelMath.list(local)
                }

                "composite" -> {
                    val here = WheelMath.times(above, own)
                    out["models"] = (leaf["models"] as List<*>).map { moved(it as Map<*, *>, vanilla, move, here) }
                }
            }
            return out
        }

        /** L = D^-1 M D: the transformation under display transform [display] that moves the drawn item by [move]. */
        fun local(
            display: DoubleArray,
            move: DoubleArray,
        ): DoubleArray = WheelMath.times(WheelMath.inverse(display), move, display)

        private fun whens(case: Map<*, *>): List<Any?> = (case["when"] as? List<*>) ?: listOf(case["when"])

        fun short(id: String): String = id.removePrefix("minecraft:")

        private val EMPTY = mapOf("type" to "minecraft:empty")

        /** The columns of [ascii]'s glyph for [c] that hold ink, and its advance: vanilla's width plus one. */
        fun glyph(
            ascii: BufferedImage,
            c: Char,
        ): Pair<List<List<Boolean>>, Int> {
            val cell = ascii.width / 16
            val gx = (c.code % 16) * cell
            val gy = (c.code / 16) * cell
            val rows = (0 until cell).map { y -> (0 until cell).map { x -> (ascii.getRGB(gx + x, gy + y) ushr 24) != 0 } }
            if (c == ' ') return rows to 4
            val width = (0 until cell).lastOrNull { x -> rows.any { it[x] } }?.plus(1) ?: 0
            return rows to width + 1
        }
    }
}
