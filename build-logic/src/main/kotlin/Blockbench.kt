package eu.nordtal.season.build

import java.math.BigDecimal
import java.math.MathContext
import java.util.Base64
import java.util.UUID

/**
 * Converts a Blockbench project of the format Java Block/Item into the model and the textures that Blockbench exports, and
 * a model back into such a project.
 */
object Blockbench {
    /**
     * A texture to import: the [name] of its file, its [png] and its `.mcmeta`, or null when it is no animation. [link] is
     * the texture id that the project names it by, or null when it names none.
     */
    class Texture(
        val name: String,
        val png: ByteArray,
        val mcmeta: ByteArray?,
        val link: String? = null,
    )

    /**
     * A texture to export: its [id], the [key] the model names it by, whether it is the [particle], its [png], whose pixel
     * [size] is width to height, and its `.mcmeta` or null.
     */
    class Embedded(
        val id: String,
        val key: String,
        val particle: Boolean,
        val png: ByteArray,
        val size: Pair<Int, Int>,
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
        references.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            throw IllegalArgumentException("Two textures of $file are named ${it.key}.")
        }
        val model = linkedMapOf<String, Any?>()
        (project["parent"] as? String)?.takeIf { it.isNotEmpty() }?.let { model["parent"] = it }
        if (project["ambientocclusion"] == false) model["ambientocclusion"] = false
        if (project["front_gui_light"] == true) model["gui_light"] = "front"

        val used = linkedSetOf<Int>()
        val elements =
            (project["elements"] as? List<*>).orEmpty().map { it as Map<*, *> }.mapNotNull { element ->
                if (element["export"] == false) return@mapNotNull null
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
                                val uv = face["uv"] as? List<*>
                                require(uv != null) { "Face $side of element ${element["name"]} of $file has no uv." }
                                put("uv", uv.mapIndexed { i, n -> scaled(decimal(n!!), if (i % 2 == 0) u else v) })
                            }
                            (face["rotation"] as? Number)?.takeIf { it.toInt() != 0 }?.let { put("rotation", it) }
                            if (source != null) used += index
                            put("texture", source?.let { "#" + it["id"] } ?: "#missing")
                            (face["cullface"] as? String)?.takeIf { it.isNotEmpty() }?.let { put("cullface", it) }
                            (face["tint"] as? Number)?.takeIf { it.toInt() >= 0 }?.let { put("tintindex", it) }
                        }
                }
                if (faces.isEmpty()) return@mapNotNull null
                linkedMapOf<String, Any?>().apply {
                    val inflate = decimal(element["inflate"] ?: 0)
                    val from = (element["from"] as List<*>).map { decimal(it!!) - inflate }
                    val to = (element["to"] as List<*>).map { decimal(it!!) + inflate }
                    require((from + to).all { it in SPACE }) {
                        "Element ${element["name"]} of $file leaves the space from -16 to 32 that a Java Block/Item model allows."
                    }
                    put("from", from.map(::number))
                    put("to", to.map(::number))
                    rotation(element)?.let { put("rotation", it) }
                    if (element["shade"] == false) put("shade", false)
                    val light = (element["light_emission"] as? Number)?.toInt()?.coerceIn(0, 15) ?: 0
                    if (light != 0) put("light_emission", light)
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
            if (particle || kept) images[reference] = image(source, reference, file, width, height).let { Texture(it.name, it.png, it.mcmeta, link(source)) }
        }
        if (textures.isNotEmpty()) model["textures"] = textures
        if (elements.isNotEmpty()) model["elements"] = elements
        display(project["display"])?.let { model["display"] = it }
        return model to images
    }

    /** The texture id of [source] by its namespace, folder and name, as Blockbench exports it, or null without a name. */
    private fun link(source: Map<*, *>): String? {
        val name = (source["name"] as? String)?.replace(Regex("""\.\w{2,8}$"""), "")?.takeIf { it.isNotEmpty() } ?: return null
        val namespace = (source["namespace"] as? String)?.takeIf { it.isNotEmpty() } ?: "minecraft"
        val folder = (source["folder"] as? String)?.trim('/')?.takeIf { it.isNotEmpty() }
        return "$namespace:" + listOfNotNull(folder, name).joinToString("/")
    }

    /**
     * The Blockbench project of the model [name], whose own JSON is [model], with [elements], its own or its nearest
     * parent's, and [textures], embedded. [keys] maps each texture key of the model and its parents to the id it draws.
     * Its UV space is 16 by 16, as the model's, so each UV stays as it is.
     */
    fun project(
        name: String,
        model: Map<*, *>,
        elements: List<Map<*, *>>,
        textures: List<Embedded>,
        keys: Map<String, String?>,
    ): Map<String, Any?> {
        val index = { key: String -> keys[key]?.let { id -> textures.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } }
        val cubes =
            elements.mapIndexed { i, element ->
                val from = (element["from"] as List<*>).map { decimal(it!!) }
                val to = (element["to"] as List<*>).map { decimal(it!!) }
                val faces = element["faces"] as? Map<*, *> ?: emptyMap<String, Any?>()
                val rotation = element["rotation"] as? Map<*, *>
                linkedMapOf<String, Any?>(
                    "name" to "cube${i + 1}",
                    "box_uv" to false,
                    "rescale" to (rotation?.get("rescale") == true),
                    "shade" to (element["shade"] != false),
                    "light_emission" to (element["light_emission"] ?: 0),
                    "from" to from.map(::number),
                    "to" to to.map(::number),
                    "autouv" to 0,
                    "rotation" to angles(rotation),
                    "origin" to ((rotation?.get("origin") as? List<*>)?.map { number(decimal(it!!)) } ?: listOf(8, 8, 8)),
                    "faces" to
                        SIDES.associateWith { side ->
                            val face = faces[side] as? Map<*, *>
                            linkedMapOf<String, Any?>(
                                "uv" to ((face?.get("uv") as? List<*>)?.map { number(decimal(it!!)) } ?: uv(side, from, to)),
                                "texture" to face?.let { (it["texture"] as? String)?.removePrefix("#")?.let(index) },
                            ).apply {
                                (face?.get("rotation") as? Number)?.let { put("rotation", it) }
                                (face?.get("cullface") as? String)?.let { put("cullface", it) }
                                (face?.get("tintindex") as? Number)?.let { put("tint", it) }
                            }
                        },
                    "type" to "cube",
                    "uuid" to UUID.nameUUIDFromBytes("$name/element/$i".toByteArray()).toString(),
                )
            }
        return linkedMapOf(
            "meta" to mapOf("format_version" to "4.10", "model_format" to "java_block", "box_uv" to false),
            "name" to name,
            "parent" to (model["parent"] as? String ?: ""),
            "ambientocclusion" to (model["ambientocclusion"] != false),
            "front_gui_light" to (model["gui_light"] == "front"),
            "resolution" to mapOf("width" to 16, "height" to 16),
            "elements" to cubes,
            "outliner" to cubes.map { it["uuid"] },
            "textures" to textures.mapIndexed { i, texture -> embedded(name, i, texture) },
            "display" to (model["display"] ?: emptyMap<String, Any?>()),
        )
    }

    /** The project's entry of [texture], at [i] of the model [name]. */
    private fun embedded(
        name: String,
        i: Int,
        texture: Embedded,
    ): Map<String, Any?> {
        val (namespace, path) = texture.id.substringBefore(':') to texture.id.substringAfter(':')
        val animation = texture.mcmeta?.let { (OxfmtJson.read(it.decodeToString()) as? Map<*, *>)?.get("animation") as? Map<*, *> }
        return linkedMapOf(
            "path" to "",
            "name" to path.substringAfterLast('/') + ".png",
            "folder" to path.substringBeforeLast('/', ""),
            "namespace" to namespace,
            "id" to texture.key,
            "width" to texture.size.first,
            "height" to texture.size.second,
            "uv_width" to 16,
            "uv_height" to 16,
            "particle" to texture.particle,
            "render_mode" to "default",
            "frame_time" to (animation?.get("frametime") ?: 1),
            "frame_order_type" to "loop",
            "frame_order" to "",
            "frame_interpolate" to (animation?.get("interpolate") == true),
            "visible" to true,
            "internal" to true,
            "saved" to true,
            "uuid" to UUID.nameUUIDFromBytes("$name/texture/$i".toByteArray()).toString(),
            "source" to "data:image/png;base64," + Base64.getEncoder().encodeToString(texture.png),
        )
    }

    /** The angles about x, y and z of a model's [rotation]. */
    private fun angles(rotation: Map<*, *>?): List<Any> {
        if (rotation == null) return listOf(0, 0, 0)
        if (rotation["axis"] != null) {
            val angle = number(decimal(rotation["angle"] ?: 0))
            return listOf("x", "y", "z").map { if (it == rotation["axis"]) angle else 0 }
        }
        return listOf("x", "y", "z").map { number(decimal(rotation[it] ?: 0)) }
    }

    /** The UV that Minecraft gives a face of a cube from [from] to [to] that names none. */
    private fun uv(
        side: String,
        from: List<BigDecimal>,
        to: List<BigDecimal>,
    ): List<Any> {
        val sixteen = BigDecimal(16)
        val (x1, y1, z1) = from
        val (x2, y2, z2) = to
        val uv =
            when (side) {
                "down" -> listOf(x1, sixteen - z2, x2, sixteen - z1)
                "up" -> listOf(x1, z1, x2, z2)
                "north" -> listOf(sixteen - x2, sixteen - y2, sixteen - x1, sixteen - y1)
                "south" -> listOf(x1, sixteen - y2, x2, sixteen - y1)
                "west" -> listOf(z1, sixteen - y2, z2, sixteen - y1)
                else -> listOf(sixteen - z2, sixteen - y2, sixteen - z1, sixteen - y1)
            }
        return uv.map(::number)
    }

    private val SIDES = listOf("north", "east", "south", "west", "up", "down")

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

    /**
     * The texture [source] of [file] as the PNG it embeds and the `.mcmeta` of its animation. A texture without a UV size
     * of its own takes the project's [width] and [height].
     */
    private fun image(
        source: Map<*, *>,
        reference: String,
        file: String,
        width: BigDecimal,
        height: BigDecimal,
    ): Texture {
        val data = source["source"] as? String
        require(data != null && data.startsWith("data:image/png;base64,")) {
            "Texture ${source["name"]} of $file is not embedded. Save the project in Blockbench with its textures embedded."
        }
        val png = Base64.getDecoder().decode(data.substringAfter(","))
        val pixels = (source["width"] as Number).toDouble() / (source["height"] as Number).toDouble()
        val u = (source["uv_width"] as? Number)?.toDouble() ?: width.toDouble()
        val v = (source["uv_height"] as? Number)?.toDouble() ?: height.toDouble()
        val frames = Math.ceil((u / v) / pixels - 0.05).toInt()
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

    /** The coordinates that an element of a Java Block/Item model may take */
    private val SPACE = BigDecimal(-16)..BigDecimal(32)

    private fun decimal(value: Any): BigDecimal = value as? BigDecimal ?: BigDecimal(value.toString())

    /** [value] scaled from a texture [size] pixels wide to the 16 units of a model */
    private fun scaled(
        value: BigDecimal,
        size: BigDecimal,
    ): Any = number((value * BigDecimal(16)).divide(size, MathContext.DECIMAL64))

    /** [value] as a whole number when it is one */
    private fun number(value: BigDecimal): Any = value.stripTrailingZeros().let { if (it.scale() <= 0) it.intValueExact() else it }
}
