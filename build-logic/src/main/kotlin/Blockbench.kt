package eu.nordtal.season.build

import java.math.BigDecimal
import java.math.MathContext
import java.util.Base64

/** Converts a Blockbench project of the format Java Block/Item into the model and the textures that Blockbench exports. */
object Blockbench {
    /** A texture to import: the [name] of its file, its [png] and its `.mcmeta`, or null when it is no animation. */
    class Texture(
        val name: String,
        val png: ByteArray,
        val mcmeta: ByteArray?,
    )

    /**
     * The model of [project], a `.bbmodel` file named [file], and its textures by the reference the model's `textures`
     * name them with. Every texture must be embedded in the project.
     */
    fun convert(
        project: Map<*, *>,
        file: String,
    ): Pair<MutableMap<String, Any?>, Map<String, Texture>> {
        val format = (project["meta"] as? Map<*, *>)?.get("model_format")
        require(format == "java_block") {
            "$file is a Blockbench project of the format $format. Only Java Block/Item models can be imported."
        }
        val resolution = project["resolution"] as? Map<*, *>
        val width = decimal(resolution?.get("width") ?: 16)
        val height = decimal(resolution?.get("height") ?: 16)
        val sources = (project["textures"] as? List<*>).orEmpty().map { it as Map<*, *> }
        val references = sources.map { (it["name"] as String).replace(Regex("""\.\w{2,8}$"""), "") }
        val model = linkedMapOf<String, Any?>()
        (project["parent"] as? String)?.takeIf { it.isNotEmpty() }?.let { model["parent"] = it }
        if (project["ambientocclusion"] == false) model["ambientocclusion"] = false
        if (project["front_gui_light"] == true) model["gui_light"] = "front"

        val used = linkedSetOf<Int>()
        val elements =
            (project["elements"] as? List<*>).orEmpty().map { it as Map<*, *> }.mapNotNull { element ->
                require((element["type"] ?: "cube") == "cube") {
                    "Element ${element["name"]} of $file is a ${element["type"]}. A Java Block/Item model holds only cubes."
                }
                val faces = linkedMapOf<String, Any?>()
                (element["faces"] as? Map<*, *>)?.forEach { (side, value) ->
                    val face = value as Map<*, *>
                    val index = texture(face["texture"], sources) ?: return@forEach
                    val source = sources.getOrNull(index)
                    val u = source?.get("uv_width")?.let(::decimal) ?: width
                    val v = source?.get("uv_height")?.let(::decimal) ?: height
                    faces[side as String] =
                        linkedMapOf<String, Any?>().apply {
                            if (face["enabled"] != false) {
                                put("uv", (face["uv"] as List<*>).mapIndexed { i, n -> scaled(decimal(n!!), if (i % 2 == 0) u else v) })
                            }
                            (face["rotation"] as? Number)?.takeIf { it.toInt() != 0 }?.let { put("rotation", it) }
                            put("texture", source?.let { used += index; "#" + it["id"] } ?: "#missing")
                            (face["cullface"] as? String)?.takeIf { it.isNotEmpty() }?.let { put("cullface", it) }
                            (face["tint"] as? Number)?.takeIf { it.toInt() >= 0 }?.let { put("tintindex", it) }
                        }
                }
                if (faces.isEmpty()) return@mapNotNull null
                linkedMapOf<String, Any?>().apply {
                    val inflate = decimal(element["inflate"] ?: 0)
                    put("from", (element["from"] as List<*>).map { number(decimal(it!!) - inflate) })
                    put("to", (element["to"] as List<*>).map { number(decimal(it!!) + inflate) })
                    rotation(element)?.let { put("rotation", it) }
                    if (element["shade"] == false) put("shade", false)
                    (element["light_emission"] as? Number)?.takeIf { it.toInt() != 0 }?.let { put("light_emission", it) }
                    put("faces", faces)
                }
            }

        val textures = linkedMapOf<String, Any?>()
        val images = linkedMapOf<String, Texture>()
        sources.forEachIndexed { index, source ->
            val reference = references[index]
            val particle = source["particle"] == true
            val kept = index in used || elements.isEmpty()
            if (particle) textures["particle"] = reference
            if (kept) textures[source["id"].toString()] = reference
            if (particle || kept) images[reference] = image(source, reference, file)
        }
        if (textures.isNotEmpty()) model["textures"] = textures
        if (elements.isNotEmpty()) model["elements"] = elements
        display(project["display"])?.let { model["display"] = it }
        return model to images
    }

    /** The index in [sources] of the texture of a face, which a project names by index or by uuid, or null for none. */
    private fun texture(
        value: Any?,
        sources: List<Map<*, *>>,
    ): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> sources.indexOfFirst { it["uuid"] == value }.takeIf { it >= 0 } ?: -1
            else -> null
        }

    /** The rotation of [element]: an angle about one axis when it turns about one axis by at most 45 degrees. */
    private fun rotation(element: Map<*, *>): Map<String, Any?>? {
        val angles = (element["rotation"] as? List<*>)?.map { decimal(it!!) } ?: List(3) { BigDecimal.ZERO }
        val origin = (element["origin"] as? List<*>)?.map { number(decimal(it!!)) } ?: listOf(8, 8, 8)
        val rescale = element["rescale"] == true
        val turned = angles.withIndex().filter { it.value.signum() != 0 }
        if (turned.isEmpty() && !rescale) return null
        val rotation = linkedMapOf<String, Any?>()
        if (turned.size > 1 || turned.any { it.value.abs() > BigDecimal(45) }) {
            listOf("x", "y", "z").forEachIndexed { i, axis -> rotation[axis] = number(angles[i]) }
        } else {
            val (axis, angle) = turned.singleOrNull()?.let { "xyz"[it.index].toString() to it.value } ?: ("y" to BigDecimal.ZERO)
            rotation["angle"] = number(angle)
            rotation["axis"] = axis
        }
        rotation["origin"] = origin
        if (rescale) rotation["rescale"] = true
        return rotation
    }

    /** The texture [source] of [file] as the PNG it embeds and the `.mcmeta` of its animation. */
    private fun image(
        source: Map<*, *>,
        reference: String,
        file: String,
    ): Texture {
        val data = source["source"] as? String
        require(data != null && data.startsWith("data:image/png;base64,")) {
            "Texture ${source["name"]} of $file is not embedded. Save the project in Blockbench with its textures embedded."
        }
        val png = Base64.getDecoder().decode(data.substringAfter(","))
        val width = (source["width"] as Number).toDouble()
        val height = (source["height"] as Number).toDouble()
        val u = (source["uv_width"] as? Number)?.toDouble() ?: width
        val v = (source["uv_height"] as? Number)?.toDouble() ?: height
        val frames = Math.ceil((u / v) / (width / height) - 0.05).toInt()
        val mcmeta =
            if (frames > 1) {
                val animation = linkedMapOf<String, Any?>("frametime" to (source["frame_time"] ?: 1))
                if (u != v) {
                    animation["width"] = number(decimal(u))
                    animation["height"] = number(decimal(v))
                }
                if (source["frame_interpolate"] == true) animation["interpolate"] = true
                order(source, frames)?.let { animation["frames"] = it }
                OxfmtJson.asset(mapOf("animation" to animation)).toByteArray()
            } else {
                null
            }
        return Texture(reference.lowercase(), png, mcmeta)
    }

    /** The frames that [source] plays in an order of its own, as Blockbench writes them, or null for each frame in turn. */
    private fun order(
        source: Map<*, *>,
        count: Int,
    ): List<Any>? =
        when (source["frame_order_type"]) {
            "backwards" -> {
                (count - 1 downTo 0).toList()
            }

            "back_and_forth" -> {
                (0 until count * 2 - 2).map { if (it >= count - 1) count * 2 - 2 - it else it }
            }

            "custom" -> {
                (source["frame_order"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("""\s+"""))?.map { frame ->
                    if (':' in frame) {
                        frame.split(':').let { mapOf("index" to it[0].toInt(), "time" to it[1].toInt()) }
                    } else {
                        frame.toInt()
                    }
                }
            }

            else -> {
                null
            }
        }

    /** The display of each slot, with the transforms that Minecraft reads */
    private fun display(value: Any?): Map<String, Any?>? =
        (value as? Map<*, *>)
            ?.mapNotNull { (slot, transform) ->
                val kept = (transform as? Map<*, *>)?.filterKeys { it in TRANSFORMS }?.mapKeys { it.key as String }
                kept?.takeIf { it.isNotEmpty() }?.let { slot as String to it }
            }?.toMap()
            ?.takeIf { it.isNotEmpty() }

    private val TRANSFORMS = setOf("rotation", "translation", "scale")

    private fun decimal(value: Any): BigDecimal = value as? BigDecimal ?: BigDecimal(value.toString())

    /** [value] scaled from a texture [size] pixels wide to the 16 units of a model */
    private fun scaled(
        value: BigDecimal,
        size: BigDecimal,
    ): Any = number((value * BigDecimal(16)).divide(size, MathContext.DECIMAL64))

    /** [value] as a whole number when it is one */
    private fun number(value: BigDecimal): Any = value.stripTrailingZeros().let { if (it.scale() <= 0) it.intValueExact() else it }
}
